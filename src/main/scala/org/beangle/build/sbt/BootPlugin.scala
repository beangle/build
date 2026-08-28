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
import CompileHookPlugin.autoImport.*
import sbt.*
import sbt.Keys.*
import sbt.librarymanagement.{Artifact, ConfigRef, ModuleReport, UpdateReport}
import xsbti.{FileConverter, HashedVirtualFileRef}

import java.io.File

/** Generates compile/runtime dependency metadata for beangle-boot.
 *
 * Two tasks, two outputs:
 *  - [[bootDependencies]] writes `META-INF/beangle/dependencies` (GAV lines only).
 *  - [[bootRepo]] copies real jars into `target/repository` (Maven layout).
 *
 * Both start from sbt [[UpdateReport]] compile/runtime modules (not classpath
 * `Attributed` metadata). SNAPSHOT / optional / test / provided stay out.
 *
 * Inter-project `.dependsOn` siblings appear in the report with empty `artifacts`
 * (classpath products, not resolver jars). [[selectBootGavs]] still lists their GAV
 * so boot can download after publish; [[selectBootArtifacts]] includes them only when
 * an exported `.jar` is on `internalDependencyClasspath` (`exportJars := true`).
 * Class directories are ignored for [[bootRepo]].
 */
object BootPlugin extends sbt.AutoPlugin {

  val DependenciesFileName = "dependencies"

  /** Resolved main jar for [[bootRepo]] (`jar` is always present).
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
    // GAV list only — siblings without a local jar are still written.
    bootDependencies := Def.uncached {
      val out = (Compile / resourceManaged).value / "META-INF" / "beangle" / DependenciesFileName
      val gavs = selectBootGavs(update.value, selfCoord = organization.value + ":" + name.value)
      Some(writeDependenciesFile(out, gavs, streams.value.log))
    },
    // Post-compile hook: generated file lands in resourceManaged and is packaged via
    // resourceDirectories (never redefine packageBin with Def.uncached self-ref — sbt 2 hang).
    Compile / compilePostHooks += Def.task {
      bootDependencies.value
      ()
    }.taskValue,
    // Local Maven repo of jars that actually exist (resolver + exported siblings).
    bootRepo := Def.uncached {
      given FileConverter = fileConverter.value
      val build = loadedBuild.value
      val base = new File(build.root) / "target/repository"
      val isRoot = baseDirectory.value.getCanonicalFile == new File(build.root).getCanonicalFile
      val artifacts = selectBootArtifacts(
        update.value,
        (Runtime / internalDependencyClasspath).value,
        selfCoord = organization.value + ":" + name.value
      )
      assembleRepo(base, artifacts, streams.value.log)
      if (isRoot) streams.value.log.info(s"project repository is generated in $base")
    }
  )

  /** Compile + runtime. Optional / test / provided are not in these configs
   * (same as excluding direct `% "optional"`; Ivy `runtime` extends `compile`).
   */
  private val BootConfigs: Seq[ConfigRef] = Seq(ConfigRef("compile"), ConfigRef("runtime"))

  /** Shared module filter: compile/runtime, not evicted, not SNAPSHOT, not self. */
  private def bootModules(report: UpdateReport, selfCoord: String): Iterator[ModuleReport] =
    BootConfigs.flatMap(report.configuration).iterator.flatMap { conf =>
      conf.modules.iterator
        .filterNot(_.evicted)
        .filterNot(_.module.revision.contains("SNAPSHOT"))
        .filterNot(mr => (mr.module.organization + ":" + mr.module.name) == selfCoord)
    }

  /** GAV lines for [[bootDependencies]] (no jar required). */
  private[sbt] def selectBootGavs(report: UpdateReport, selfCoord: String): Seq[String] =
    bootModules(report, selfCoord).flatMap(moduleBootGavs).distinct.toSeq.sorted

  /** Jars for [[bootRepo]]: UpdateReport main jars + exported sibling jars only. */
  private[sbt] def selectBootArtifacts(
    report: UpdateReport,
    internal: Seq[Attributed[HashedVirtualFileRef]],
    selfCoord: String
  )(using FileConverter): Seq[BootArtifact] = {
    // organization:name -> exported sibling jar (`exportJars`); class dirs omitted.
    val internalJars = internal.flatMap { entry =>
      Utils.moduleId(entry).flatMap { m =>
        val product = Utils.file(entry)
        if (product.isFile && product.getName.endsWith(".jar"))
          Some((m.organization + ":" + m.name) -> product)
        else None
      }
    }.toMap
    bootModules(report, selfCoord).flatMap(moduleBootArtifacts(_, internalJars)).distinctBy(_.gav).toSeq.sortBy(_.gav)
  }

  /** One module → GAV line(s). Empty artifacts ⇒ inter-project dep; use [[ModuleID.name]]. */
  private[sbt] def moduleBootGavs(mr: ModuleReport): Seq[String] = {
    val fromArts = mr.artifacts.iterator.collect {
      case (art, _) if isMainJar(art) => s"${mr.module.organization}:${art.name}:${mr.module.revision}"
    }.toSeq
    if (fromArts.nonEmpty) fromArts
    else if (mr.artifacts.isEmpty && mr.missingArtifacts.isEmpty)
      Seq(s"${mr.module.organization}:${mr.module.name}:${mr.module.revision}")
    else Nil
  }

  /** One module → [[BootArtifact]] with a real jar, or empty if none (e.g. classes-only sibling). */
  private[sbt] def moduleBootArtifacts(mr: ModuleReport, internalJars: Map[String, File]): Seq[BootArtifact] = {
    val mains = mr.artifacts.iterator.collect {
      case (art, jar) if isMainJar(art) =>
        BootArtifact(mr.module.organization, art.name, mr.module.revision, jar)
    }.toSeq
    if (mains.nonEmpty) mains
    else if (mr.artifacts.isEmpty && mr.missingArtifacts.isEmpty) {
      val coord = mr.module.organization + ":" + mr.module.name
      internalJars.get(coord).map(jar =>
        BootArtifact(mr.module.organization, mr.module.name, mr.module.revision, jar)
      ).toSeq
    } else Nil
  }

  /** Classifier-less jar/bundle only — keeps each dependencies line as 3-part GAV. */
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

  private def writeDependenciesFile(file: File, gavs: Seq[String], log: Logger): File = {
    val lines = gavs.map(requireMavenGav)
    IO.createDirectory(file.getParentFile)
    IO.write(file, lines.mkString("\n"))
    log.info(s"generated ${gavs.size} dependencies at ${file.getAbsolutePath}")
    file
  }

  /** Copy jars (+ sidecars `.sha1` when present) into Maven layout under `projectRepoDir`. */
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
