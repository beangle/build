/*
 * Copyright (C) 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import sbt.*
import sbt.Keys.*
import xsbti.FileConverter

import java.io.File
import scala.collection.mutable
import scala.jdk.javaapi.CollectionConverters.asJava

object TomcatPlugin extends sbt.AutoPlugin {

  object autoImport {
    val tomcatStart = inputKey[Unit]("start tomcat server")

    lazy val baseSettings: Seq[Setting[?]] = Seq(
      libraryDependencies ++= Seq(Sas.Engine, Sas.TomcatCore, Sas.TomcatWebSocket, Sas.JulToSlf4j),
      tomcatStart := Def.inputTaskDyn {
        import complete.DefaultParsers.*
        val args = spaceDelimited("<arg>").parsed
        Def.task {
          given FileConverter = fileConverter.value
          val jars = CpFiles.files((Test / fullClasspath).value)
            .filterNot(f => f.isDirectory && f.getName == "test-classes")
          launchTomcat(crossTarget.value.getAbsolutePath, jars, args, streams.value.log)
        }
      }.evaluated
    )
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] = baseSettings

  private def launchTomcat(target: String, dependencies: Seq[File], args: Seq[String], log: util.Logger): Unit = {
    val folder = new File(target + "/tomcat/")
    folder.mkdirs()
    val classpath = dependencies.map(_.getAbsolutePath).mkString(File.pathSeparator)
    try {
      val cmds = new mutable.ArrayBuffer[String]
      cmds += "java"
      cmds += "-cp"
      cmds += classpath
      cmds += "org.beangle.sas.engine.tomcat.Bootstrap"
      cmds += "--dev=true"
      cmds ++= args
      val pb = new ProcessBuilder(asJava(cmds))
      pb.inheritIO()
      val pro = pb.start()
      pro.waitFor()
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }
}
