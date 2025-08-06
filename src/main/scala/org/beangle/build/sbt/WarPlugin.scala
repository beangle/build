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
import org.beangle.build.util.{Bsdiff, Files, IOs}
import sbt.*
import sbt.Def.taskKey
import sbt.Keys.*

import java.io.{File, FileInputStream, FileOutputStream}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.jar.Manifest

object WarPlugin extends AutoPlugin {

  import Keys.`package` as pkg

  object autoImport {
    lazy val warPrepare = taskKey[Seq[(File, String)]]("prepare webapp contents for packaging")
    lazy val warAddDefaultWebxml = settingKey[Boolean]("add default web.xml when nessesary")
    val warDiff = inputKey[Unit]("Generate war diff")
    lazy val warBuild = taskKey[File]("build snapshot with timestamp war")
  }

  import autoImport.*

  override def requires = BootPlugin

  private def manifestOptions =
    Def.task {
      val opt = (packageBin / packageOptions).value
      opt.filter {
        case _: Package.ManifestAttributes => true
        case _ => false
      }
    }

  override def projectSettings: Seq[Setting[_]] = {
    Defaults.packageTaskSettings(pkg, warPrepare) ++
      Seq(pkg / artifact := Artifact(moduleName.value, "war", "war")) ++
      addArtifact(Compile / pkg / artifact, pkg) ++
      Seq(pkg / packageOptions ++= manifestOptions.value) ++
      Seq(
        (warPrepare / sourceDirectory) := (Compile / sourceDirectory).value / "webapp",
        (warPrepare / target) := (Compile / target).value / "webapp",
        warPrepare := webappPrepareTask.value,
        warDiff := {
          val a = (Compile / Keys.`package` / artifact).value
          if (a.extension == "war") {
            import complete.DefaultParsers.*
            val args = spaceDelimited("<arg>").parsed
            val log = streams.value.log
            if (args.size < 1) {
              log.error("usage:warDiff oldVersion [newVersion]")
            } else {
              val m = (Compile / projectID).value
              resolvers.value.find(_.isInstanceOf[MavenRepository]) foreach { mc =>
                val m2Root = mc.asInstanceOf[MavenRepository].root
                calcWarDiff(m2Root, m, args.head, args.tail.headOption.getOrElse(m.revision),
                  crossTarget.value.getAbsolutePath, scalaBinaryVersion.value, log)
              }
            }
          }
        },
        warBuild := warBuildTask.value,
        warBuild := warBuild.dependsOn(pkg).value,
        warAddDefaultWebxml := true)
  }

  private def prepareWebxml(webappSrcDir: File, log: util.Logger): Unit = {
    val buildWebInf = s"${webappSrcDir.getAbsolutePath}/WEB-INF/"
    val webxml = new File(buildWebInf + "/web.xml")
    new File(buildWebInf).mkdirs()
    if (!webxml.exists()) {
      val os = new FileOutputStream(webxml)
      IOs.copy(getClass.getResourceAsStream("/org/beangle/build/web/web.xml"), os)
      IOs.close(os)
      log.info(s"Append default web.xml ${webxml.getAbsolutePath}")
    }
  }

  private def warBuildTask = {
    Def.task {
      val a = (Compile / Keys.`package` / artifact).value
      val dir = (warBuild / target).value.getAbsolutePath + "/"
      val file = new File(dir + a.name + "-" + version.value + "." + a.extension)
      val log = streams.value.log
      if (file.exists()) {
        val formater = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")
        val buildNumber = formater.format(LocalDateTime.now) + "-1"
        val build = new File(dir + a.name + "-" + version.value.replace("-SNAPSHOT", "") + "-" + buildNumber + "." + a.extension)
        if (build.exists()) {
          build.delete()
        }
        Files.copy(file, build)
        log.info(s"Build ${build.getAbsolutePath}")
        build
      } else {
        log.warn(s"Cannot find ${file.getName},build snapshot is aborted.")
        file
      }
    }
  }

  private def webappPrepareTask =
    Def.task {
      // 1. copy src/main/webapp to target/webapp
      val webappTarget = assembleWebapp(warPrepare / target, "webapp").value
      val log = streams.value.log
      // 1.1 generate default web.xml and dependencies file
      if (warAddDefaultWebxml.value) prepareWebxml(webappTarget, log)

      // 2. copy project's classes to WEB-INF/classes
      val m = (Compile / packageBin / mappings).value
      val webInfDir = webappTarget / "WEB-INF"
      val webappLibDir = webInfDir / "lib"

      val taskStreams = streams.value
      Utils.cacheify(
        "classes",
        { in =>
          m find (_._1 == in) map (webInfDir / "classes" / _._2)
        },
        (m filter (!_._1.isDirectory) map (_._1)).toSet,
        taskStreams
      )

      //2.2 generate and copy dependencies
      val dependencyFile = BootPlugin.bootDependenciesTask.value
      dependencyFile foreach { df =>
        val beangleDir = s"${webInfDir.getAbsolutePath}/classes/META-INF/beangle"
        new File(beangleDir).mkdirs()
        val is = new FileInputStream(df)
        val os = new FileOutputStream(beangleDir + "/" + BootPlugin.DependenciesFileName)
        IOs.copy(is, os)
        IOs.close(os)
      }

      // 3. create .jar files for depended-on projects
      val classpath = (Runtime / fullClasspath).value
      if (version.value.contains("SNAPSHOT")) {
        for {
          cpItem <- classpath.toList
          dir = cpItem.data
          if dir.isDirectory
          artEntry <- cpItem.metadata.entries find { e =>
            e.key.label == "artifact"
          }
          cpArt = artEntry.value.asInstanceOf[Artifact]
          artifact = (Compile / packageBin / packagedArtifact).value._1
          if cpArt != artifact
          files = (dir ** "*").get flatMap { file =>
            if (!file.isDirectory)
              IO.relativize(dir, file) map { p => (file, p) }
            else
              None
          }
          jarFile = cpArt.name + ".jar"
          _ = Utils.jar(
            sources = files,
            outputJar = webappLibDir / jarFile,
            manifest = new Manifest
          )
        } yield ()
      }

      // 4. copy SNAPSHOT dependency libraries to WEB-INF/lib
      Utils.cacheify(
        "lib-deps",
        { in => Some(webappTarget / "WEB-INF" / "lib" / in.getName) },
        classpath.map(_.data).toSet filter { in =>
          !in.isDirectory && in.getAbsolutePath.contains("-SNAPSHOT") && in.getName.endsWith(".jar")
        },
        taskStreams
      )

      (webappTarget ** "*") pair (Path.relativeTo(webappTarget) | Path.flat)
    }

  /** assemble web files
   * copy src/main/webapp to target/webapp
   *
   * @param webappTarget target/webapp
   * @param cacheName
   * @return
   */
  private def assembleWebapp(webappTarget: SettingKey[File], cacheName: String): Def.Initialize[Task[File]] = {
    Def.task {
      val webappSrcDir = (warPrepare / sourceDirectory).value //src/main/webapp
      Utils.cacheify(
        cacheName,
        { in =>
          for {
            f <- Some(in)
            if !f.isDirectory
            r <- IO.relativizeFile(webappSrcDir, f)
          } yield IO.resolve(webappTarget.value, r)
        },
        (webappSrcDir ** "*").get.toSet,
        streams.value
      )
      webappTarget.value
    }
  }

  private def calcWarDiff(m2Root: String, m: sbt.librarymanagement.ModuleID, oldVersion: String,
                          newVersion: String, crossTargetDir: String, sbv: String, log: util.Logger): Unit = {
    val aname = artifactName(m, sbv)
    val oldWar = new File(Dependency.m2Path(m2Root, m.organization, aname, oldVersion, ".war"))
    val newWar = new File(Dependency.m2Path(m2Root, m.organization, aname, newVersion, ".war"))

    if (!oldWar.exists) {
      log.error(s"Cannot find ${oldWar.getPath}")
    } else if (!newWar.exists) {
      log.error(s"Cannot find ${newWar.getPath}")
    } else {
      val diffFile = new File(Dependency.m2DiffPath(m2Root, m.organization, aname, oldVersion, newVersion, ".war"))
      log.info(s"Generating diff file ${diffFile.getPath}")
      Bsdiff.diff(oldWar, newWar, diffFile)
      import Files./
      Files.copy(diffFile, new File(crossTargetDir + / + diffFile.getName))
      log.info(s"Generated ${crossTargetDir}${/}${diffFile.getName}(${diffFile.length / 1000.0}KB)")
    }
  }

  private def artifactName(m: sbt.librarymanagement.ModuleID, sbv: String): String = {
    m.crossVersion match {
      case sbt.librarymanagement.Disabled => m.name
      case _: sbt.librarymanagement.Binary => m.name + "_" + sbv
      case _ => m.name
    }
  }
}
