/*
 * Copyright (C) 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import org.beangle.build.boot.Dependency
import org.beangle.build.util.Files
import sbt.*
import sbt.Keys.*
import sbt.librarymanagement.{Artifact, ConfigRef, UpdateReport}

import java.io.File

/** Generates compile/runtime dependency metadata for beangle-boot.
 *
 * Uses sbt 2 [[UpdateReport]] (not classpath `Attributed` metadata) so ModuleID /
 * Artifact / jar File come from the resolver directly.
 */
object BootPlugin extends sbt.AutoPlugin {

  val DependenciesFileName = "dependencies"

  /** One resolved main jar suitable for beangle-boot.
   *
   * Coordinates are strict Maven GAV (`groupId:artifactId:version`).
   * Scala binary suffixes (`_2.13`, `_3`, …) belong in `artifactId`, not in a 4th field
   * and not in `version` — matching Maven Central paths and beangle-boot's 3-part parser.
   */
  final case class BootArtifact(organization: String, name: String, revision: String, jar: File) {
    def coord: String = s"$organization:$name"

    /** Maven GAV line: `groupId:artifactId:version` (exactly three colon-separated parts). */
    def gav: String = s"$organization:$name:$revision"
  }

  object autoImport {
    val bootDependencies = taskKey[Option[File]]("Generate META-INF/beangle/dependencies from UpdateReport")
    val bootRepo = taskKey[Unit]("Assemble non-SNAPSHOT compile/runtime jars into target/repository (Maven layout)")
  }

  import autoImport.*

  override def trigger = allRequirements

  override val projectSettings: Seq[Setting[?]] = Seq(
    bootDependencies := Def.uncached {
      val out = (Compile / resourceManaged).value / "META-INF" / "beangle" / DependenciesFileName
      val artifacts = selectBootArtifacts(update.value, selfCoord = organization.value + ":" + name.value)
      Some(writeDependenciesFile(out, artifacts, streams.value.log))
    },
    // Package via resourceGenerators — never redefine packageBin with Def.uncached self-ref (sbt 2 hang).
    Compile / resourceGenerators += Def.task {
      bootDependencies.value.toSeq
    }.taskValue,
    bootRepo := Def.uncached {
      val build = loadedBuild.value
      val base = new File(build.root) / "target/repository"
      val isRoot = baseDirectory.value.getCanonicalFile == new File(build.root).getCanonicalFile
      val artifacts = selectBootArtifacts(update.value, selfCoord = organization.value + ":" + name.value)
      assembleRepo(base, artifacts, streams.value.log)
      if (isRoot) streams.value.log.info(s"project repository is generated in $base")
    }
  )

  /** Compile + runtime jars. Optional / test stay out of `dependencies`
   * (same as excluding direct `% "optional"`; Ivy `runtime` extends `compile`).
   */
  private val BootConfigs: Seq[ConfigRef] = Seq(ConfigRef("compile"), ConfigRef("runtime"))

  /** Non-SNAPSHOT main jars from [[UpdateReport]] compile/runtime for boot packaging.
   *
   * Uses resolved [[Artifact]].name as Maven `artifactId` (already includes `_2.13` / `_3`
   * when published that way). Emits only classifier-less jars so each line stays 3-part GAV.
   * Optional / test / provided configurations are not included.
   */
  private[sbt] def selectBootArtifacts(report: UpdateReport, selfCoord: String): Seq[BootArtifact] = {
    BootConfigs.flatMap(report.configuration).flatMap { conf =>
      conf.modules.iterator
        .filterNot(_.evicted)
        .filterNot(_.module.revision.contains("SNAPSHOT"))
        .filterNot(mr => (mr.module.organization + ":" + mr.module.name) == selfCoord)
        .flatMap { mr =>
          mr.artifacts.iterator.collect {
            case (art, jar) if isMainJar(art) =>
              BootArtifact(mr.module.organization, art.name, mr.module.revision, jar)
          }
        }
    }.distinctBy(_.gav).sortBy(_.gav)
  }

  private def isMainJar(art: Artifact): Boolean =
    art.extension == "jar" &&
      art.classifier.isEmpty &&
      (art.`type` == "jar" || art.`type` == "bundle")

  /** Validates Maven GAV: non-empty groupId:artifactId:version (exactly 3 parts, no packaging/classifier). */
  private[sbt] def requireMavenGav(gav: String): String = {
    val parts = gav.split(":", -1)
    require(
      parts.length == 3 && parts.forall(_.nonEmpty),
      s"boot dependency must be Maven GAV groupId:artifactId:version, got: $gav"
    )
    gav
  }

  private def writeDependenciesFile(file: File, artifacts: Seq[BootArtifact], log: Logger): File = {
    val lines = artifacts.map(a => requireMavenGav(a.gav))
    IO.createDirectory(file.getParentFile)
    IO.write(file, lines.mkString("\n"))
    log.info(s"generated ${artifacts.size} dependencies at ${file.getAbsolutePath}")
    file
  }

  private def assembleRepo(projectRepoDir: File, artifacts: Seq[BootArtifact], log: Logger): Unit = {
    projectRepoDir.mkdirs()
    artifacts.foreach { a =>
      val dest = new File(Dependency.m2Path(projectRepoDir.getAbsolutePath, a.organization, a.name, a.revision))
      val destSha1 = new File(dest.getAbsolutePath + ".sha1")
      if (!dest.exists()) Files.copy(a.jar, dest)
      if (!destSha1.exists()) {
        val sha1File = new File(a.jar.getAbsolutePath + ".sha1")
        if (sha1File.exists()) Files.copy(sha1File, destSha1)
        else log.warn(s"Missing sha1 for ${dest.getAbsolutePath}")
      }
    }
  }
}
