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

import sbt.Keys.*
import sbt.*
import xsbti.FileConverter

import java.io.File

object OrmPlugin extends sbt.AutoPlugin {

  object autoImport {
    val ormDdl = taskKey[Unit]("Generate orm ddl files")

    lazy val baseOrmSettings: Seq[Def.Setting[?]] = Seq(
      ormDdl := Def.uncached(ormDdlTask.value)
    )
  }

  import autoImport.*

  override def trigger = allRequirements

  override lazy val projectSettings = inConfig(Compile)(baseOrmSettings)

  lazy val ormDdlTask =
    Def.task {
      given FileConverter = fileConverter.value
      val jars = CpFiles.files((Runtime / fullClasspath).value)
      generate(crossTarget.value.getAbsolutePath, jars, streams.value.log)
    }

  private def generate(target: String, dependencies: Seq[File], log: util.Logger): Unit = {
    val folder = new File(target + "/db/")
    folder.mkdirs()
    val classpath = dependencies.map(_.getAbsolutePath).mkString(File.pathSeparator)
    try {
      val pb = new ProcessBuilder("java", "-cp", classpath, "org.beangle.data.orm.DdlGenerator",
        "PostgreSQL,Mysql,H2,Oracle,Db2,Sqlserver", folder.getCanonicalPath, "zh_CN")
      log.debug(pb.command().toString)
      pb.inheritIO()
      val pro = pb.start()
      pro.waitFor()
      val warningFile = new File(folder.getCanonicalPath + "/warnings.txt")
      val hasWarning = warningFile.exists()
      log.info("DDl generated in " + folder.getCanonicalPath)
      if (hasWarning) {
        log.warn("Found some warnings in " + warningFile.getCanonicalPath)
      }
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }
}
