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
import sbt.Keys.*
import sbt.*

import java.io.{File, FileWriter, IOException}
import scala.collection.mutable

object BootPlugin extends sbt.AutoPlugin {

  val DependenciesFileName = "dependencies"

  object autoImport {
    val bootDependencies = taskKey[Option[File]]("Generate boot dependencies file")
    val bootRepo = taskKey[Unit]("Assemble boot dependencies to make a repo")

    lazy val bootSettings: Seq[Def.Setting[?]] = Seq(
      bootDependencies := Def.uncached(bootDependenciesTask.value),
      bootRepo := Def.uncached(bootRepoTask.value),
      Compile / packageBin := Def.uncached((Compile / packageBin).dependsOn(bootDependencies).value)
    )
  }

  import autoImport.*

  override val projectSettings = inConfig(Compile)(bootSettings)

  override def trigger = allRequirements

  lazy val bootDependenciesTask =
    Def.task {
      given FileConverter = fileConverter.value
      val excludeGavs = Set(organization.value + ":" + name.value) ++ findOptionalGavs(libraryDependencies.value)
      generate(
        crossTarget.value.getAbsolutePath,
        (Runtime / fullClasspath).value,
        scalaBinaryVersion.value,
        excludeGavs,
        streams.value.log
      )
    }

  lazy val bootRepoTask =
    Def.task {
      given FileConverter = fileConverter.value
      val build = loadedBuild.value
      val base = new File(build.root) / "target/repository"
      val isRoot = baseDirectory.value.getCanonicalFile == new File(build.root).getCanonicalFile
      val excludeGavs = Set(organization.value + ":" + name.value) ++ findOptionalGavs(libraryDependencies.value)
      assemble(base, (Runtime / fullClasspath).value, scalaBinaryVersion.value, excludeGavs, streams.value.log)
      if (isRoot) {
        streams.value.log.info(s"project repository is generated in ${base}")
      }
    }

  private def findOptionalGavs(dependencies: collection.Seq[ModuleID]): Set[String] = {
    val optionals = new mutable.HashSet[String]
    dependencies foreach { m =>
      val scope = m.configurations.getOrElse("compile")
      if (scope == "optional") {
        optionals.add(m.organization + ":" + m.name)
      }
    }
    optionals.toSet
  }

  private def generate(target: String, dependencies: collection.Seq[Attributed[?]], sbv: String,
                       excludeGavs: Set[String], log: util.Logger)(using FileConverter): Option[File] = {
    val folder = target + "/classes/META-INF/beangle"
    new File(folder).mkdirs()
    val file = new File(folder + "/" + DependenciesFileName)
    file.delete()
    try {
      file.createNewFile()
      val results = new collection.mutable.HashSet[String]
      dependencies foreach { d =>
        Utils.moduleId(d) match {
          case Some(m) =>
            val gav = m.organization + ":" + m.name
            val scope = m.configurations.getOrElse("compile")
            if (!excludeGavs.contains(gav) && "test" != scope && !m.revision.contains("SNAPSHOT")) {
              results += toGav(m, sbv)
            }
          case _ =>
        }
      }
      val fw = new FileWriter(file)
      try fw.write(results.toSeq.sorted.mkString("\n"))
      finally fw.close()
      log.info(s"generated ${results.size} dependencies at " + file.getAbsolutePath)
      Some(file)
    } catch {
      case e: IOException => e.printStackTrace(); None
    }
  }

  private def assemble(projectRepoDir: File, dependencies: collection.Seq[Attributed[?]], sbv: String,
                       excludeGavs: Set[String], log: util.Logger)(using FileConverter): Unit = {
    projectRepoDir.mkdirs()
    val artifacts = new collection.mutable.ArrayBuffer[Attributed[?]]
    dependencies foreach { d =>
      Utils.moduleId(d) match {
        case Some(m) =>
          val gav = m.organization + ":" + m.name
          val scope = m.configurations.getOrElse("compile")
          if (!excludeGavs.contains(gav) && "test" != scope && !m.revision.contains("SNAPSHOT")) artifacts += d
        case _ =>
      }
    }
    copy(artifacts, projectRepoDir, sbv, log)
  }

  private def copy(artifacts: collection.Seq[Attributed[?]], base: File, sbv: String, log: util.Logger)(using FileConverter): Unit = {
    artifacts foreach { artifact =>
      toMavenRepoPath(base.getAbsolutePath, artifact, sbv) foreach { path =>
        val dest = new File(path)
        val destSha1 = new File(path + ".sha1")
        val src = Utils.file(artifact)
        if (!dest.exists()) Files.copy(src, dest)
        if (!destSha1.exists()) {
          val sha1File = new File(src.getAbsolutePath + ".sha1")
          if (sha1File.exists()) {
            Files.copy(sha1File, new File(path + ".sha1"))
          } else {
            log.warn(s"Missing sha1 for $path")
          }
        }
      }
    }
  }

  private def toGav(m: sbt.librarymanagement.ModuleID, sbv: String): String = {
    s"${m.organization}:${artifactName(m, sbv)}:${m.revision}"
  }

  private def artifactName(m: sbt.librarymanagement.ModuleID, sbv: String): String = {
    m.crossVersion match {
      case sbt.librarymanagement.Disabled => m.name
      case _: sbt.librarymanagement.Binary => m.name + "_" + sbv
      case _ => m.name
    }
  }

  private def toMavenRepoPath(base: String, d: Attributed[?], sbv: String): Option[String] = {
    Utils.moduleId(d) match {
      case Some(m) => Some(Dependency.m2Path(base, m.organization, artifactName(m, sbv), m.revision))
      case _ => None
    }
  }

}
