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

import org.beangle.build.util.Files
import org.beangle.build.util.Files./
import sbt.Keys._
import sbt.{File, _}

import java.io.{FileWriter, IOException}

object BootPlugin extends sbt.AutoPlugin {

  private val fileName = "dependencies"

  object autoImport {
    val bootDependencies = taskKey[Unit]("Generate boot dependencies file")
    val bootRepo = taskKey[Unit]("Assemble boot dependencies to make a repo")

    lazy val baseBootSettings: Seq[Def.Setting[_]] = Seq(
      bootDependencies := generateDependenciesTask.value,
      bootRepo := assembleDependenciesTask.value,
    )
  }

  import autoImport._

  override def requires: Plugins = {
    plugins.IvyPlugin
  }

  override lazy val projectSettings = inConfig(Compile)(baseBootSettings)

  override def trigger = allRequirements

  lazy val generateDependenciesTask =
    Def.task {
      generate(crossTarget.value.getAbsolutePath, bootClasspathsTask.value, streams.value.log)
    }

  lazy val assembleDependenciesTask =
    Def.task {
      val build = loadedBuild.value
      val base = new File(build.root) / "target/repository"
      val isRoot = build.root == baseDirectory.value.toURI
      val log = streams.value.log
      assemble(base, bootClasspathsTask.value, log)
      if (isRoot) {
        log.info(s"Project reposistory is generated at ${base}")
      }
    }

  lazy val bootClasspathsTask = {
    Def.task {
      val classpaths = new collection.mutable.ArrayBuffer[Attributed[File]]
      classpaths ++= (Compile / externalDependencyClasspath).value
      classpaths ++= (Runtime / externalDependencyClasspath).value
      classpaths ++= (Compile / internalDependencyClasspath).value
      classpaths
    }
  }

  private def generate(target: String, dependencies: collection.Seq[Attributed[File]], log: util.Logger): Unit = {
    val folder = target + "/classes/META-INF/beangle"
    new File(folder).mkdirs()
    val file = new File(folder + "/" + fileName)
    file.delete()
    try {
      file.createNewFile()
      val results = new collection.mutable.HashSet[String]
      dependencies foreach { d =>
        d.get(Keys.moduleID.key) match {
          case Some(m) =>
            val scope = m.configurations.getOrElse("compile")
            if ("test" != scope && !m.revision.contains("SNAPSHOT")) {
              results += toGav(m)
            }
          case _ =>
        }
      }
      val fw = new FileWriter(file)
      fw.write(results.toSeq.sorted.mkString("\n"))
      fw.close()
      log.info(s"Generated dependencies:(${results.size}) at " + file.getAbsolutePath)
    } catch {
      case e: IOException => e.printStackTrace()
    }
  }

  /** Assemble dependencies to repository
   *
   * @param projectRepoDir
   * @param dependencies
   * @param log
   */
  private def assemble(projectRepoDir: File, dependencies: collection.Seq[Attributed[File]], log: util.Logger): Unit = {
    projectRepoDir.mkdirs()
    val artifacts = new collection.mutable.ArrayBuffer[Attributed[File]]
    dependencies foreach { d =>
      d.get(Keys.moduleID.key) match {
        case Some(m) =>
          val scope = m.configurations.getOrElse("compile")
          if ("test" != scope && !m.revision.contains("SNAPSHOT")) artifacts += d
        case _ =>
      }
    }
    copy(artifacts, projectRepoDir, log)
  }

  private def copy(artifacts: collection.Seq[Attributed[File]], base: File, log: util.Logger): Unit = {
    artifacts foreach { artifact =>
      toMavenRepoPath(artifact) foreach { path =>
        val dest = new File(base.getAbsolutePath + / + path)
        val destSha1 = new File(base.getAbsolutePath + / + path + ".sha1")
        if (!dest.exists()) Files.copy(artifact.data, dest)
        if (!destSha1.exists()) {
          val sha1File = new File(artifact.data.getAbsolutePath + ".sha1")
          if (sha1File.exists()) {
            Files.copy(sha1File, new File(base.getAbsolutePath + / + path + ".sha1"))
          } else {
            log.warn(s"Missing sha1 for $path")
          }
        }
      }
    }
  }

  private def toGav(m: sbt.librarymanagement.ModuleID): String = {
    s"${m.organization}:${artifactName(m)}:${m.revision}"
  }

  private def artifactName(m: sbt.librarymanagement.ModuleID): String = {
    m.crossVersion match {
      case sbt.librarymanagement.Disabled => m.name
      case _: sbt.librarymanagement.Binary => m.name + "_" + scalaBinaryVersion
      case _ => m.name
    }
  }

  private def toMavenRepoPath(d: Attributed[File]): Option[String] = {
    d.get(Keys.moduleID.key) match {
      case Some(m) =>
        val aname = artifactName(m)
        Some(s"${m.organization.replace('.', '/')}/${aname}/${m.revision}/${aname}-${m.revision}.jar")
      case _ => None
    }
  }

}
