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

import org.beangle.build.sbt.OrmPlugin.autoImport.ormDdlDiff
import org.beangle.build.sbt.OrmPlugin.diff
import org.beangle.build.util.IOs
import sbt.Def.taskKey
import sbt.Keys._
import sbt._

import java.io.FileOutputStream
import java.util.jar.Manifest

object WarPlugin extends AutoPlugin {

  import Keys.{`package` => pkg}

  object autoImport {
    lazy val webappPrepare = taskKey[Seq[(File, String)]]("prepare webapp contents for packaging")
    val warAddDefaultWebxml = settingKey[Boolean]("add default web.xml when nessesary")
    var warDiff = inputKey[Unit]("Generate war diff")
  }

  import autoImport._

  override def requires = BootPlugin

  private def manifestOptions =
    Def.task {
      val opt = (packageBin / packageOptions).value
      opt.filter {
        case x: Package.ManifestAttributes => true
        case _ => false
      }
    }

  override def projectSettings: Seq[Setting[_]] = {
    Defaults.packageTaskSettings(pkg, webappPrepare) ++
      Seq(pkg / artifact := Artifact(moduleName.value, "war", "war")) ++
      addArtifact(Compile / pkg / artifact, pkg) ++
      Seq(pkg / packageOptions ++= manifestOptions.value) ++
      Seq(
        (webappPrepare / sourceDirectory) := (Compile / sourceDirectory).value / "webapp",
        (webappPrepare / target) := (Compile / target).value / "webapp",
        webappPrepare := webappPrepareTask.value,
        warAddDefaultWebxml := true ) ++
      Seq(
        warDiff := {
          import complete.DefaultParsers._
          val args = spaceDelimited("<arg>").parsed
          val log = streams.value.log
          if (args.size < 2) {
            log.error("usage:warDiff oldVersion newVersion")
          } else {
            println(args)
          }
        }
      )
  }

  private def _webappPrepare(webappTarget: SettingKey[File], cacheName: String) =
    Def.task {
      val webappSrcDir = (webappPrepare / sourceDirectory).value
      Util.cacheify(
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

  private def prepareWebxml(webappSrcDir: File, log: util.Logger): Unit = {
    val buildWebInf = s"${webappSrcDir.getAbsolutePath}/WEB-INF/"
    val webxml = new File(buildWebInf + "/web.xml")
    new File(buildWebInf).mkdirs()
    if (!webxml.exists()) {
      val os = new FileOutputStream(webxml)
      IOs.copy(getClass.getResourceAsStream("/org/beangle/build/web/web.xml"), os)
      IOs.close(os)
      log.info(s"Add default web.xml ${webxml.getAbsolutePath}")
    }
  }

  private def webappPrepareTask =
    Def.task {
      val taskStreams = streams.value
      val webappTarget = _webappPrepare(webappPrepare / target, "webapp").value

      // generate default web.xml and dependencies file
      val log = streams.value.log
      if (warAddDefaultWebxml.value) prepareWebxml(webappTarget, log)
      BootPlugin.generateDependenciesTask.value

      val m = (Compile / packageBin / mappings).value
      val webInfDir = webappTarget / "WEB-INF"
      val webappLibDir = webInfDir / "lib"

      // copy project's classes to WEB-INF/classes
      Util.cacheify(
        "classes",
        { in =>
          m find (_._1 == in) map (webInfDir / "classes" / _._2)
        },
        (m filter (!_._1.isDirectory) map (_._1)).toSet,
        taskStreams
      )

      val classpath = (Runtime / fullClasspath).value
      if (version.value.contains("SNAPSHOT")) {
        // create .jar files for depended-on projects
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
          _ = Util.jar(
            sources = files,
            outputJar = webappLibDir / jarFile,
            manifest = new Manifest
          )
        } yield ()
      }

      // copy SNAPSHOT dependency libraries to WEB-INF/lib
      Util.cacheify(
        "lib-deps",
        { in => Some(webappTarget / "WEB-INF" / "lib" / in.getName) },
        classpath.map(_.data).toSet filter { in =>
          !in.isDirectory && in.getName.contains("SNAPSHOT") && in.getName.endsWith(".jar")
        },
        taskStreams
      )

      (webappTarget ** "*") pair (Path.relativeTo(webappTarget) | Path.flat)
    }

}
