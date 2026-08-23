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

object DdlPlugin extends sbt.AutoPlugin {

  var BeangleSqlplusVersion = "0.2.5"

  object autoImport {
    val ddlDiff = inputKey[Unit]("Generate ddl diff")
    val ddlReport = taskKey[Unit]("Generate ddl report")

    lazy val baseOrmSettings: Seq[Def.Setting[?]] = Seq(
      ddlReport := Def.uncached(ddlReportTask.value),
      ddlDiff := Def.inputTaskDyn {
        import complete.DefaultParsers.*
        val args = spaceDelimited("<arg>").parsed
        Def.task {
          given FileConverter = fileConverter.value
          val log = streams.value.log
          if (args.size < 2) {
            log.error("usage:ormDdlDiff oldVersion newVersion")
          } else {
            val jars = CpFiles.files((Runtime / fullClasspath).value)
            diff(baseDirectory.value, crossTarget.value, jars,
              "PostgreSQL".toLowerCase(), args(0), args(1), log)
          }
        }
      }.evaluated
    )
  }

  import autoImport.*

  override def trigger = allRequirements

  override lazy val projectSettings = inConfig(Compile)(baseOrmSettings)

  lazy val ddlReportTask =
    Def.task {
      val reportPath = "/src/main/resources/db/postgresql/report.xml"
      val reportXML = new File(baseDirectory.value, reportPath.stripPrefix("/"))
      val log = streams.value.log
      resolvers.value.collectFirst { case mc: MavenRepository => mc.root }.foreach { m2Root =>
        if (reportXML.exists()) {
          report(m2Root, reportXML, crossTarget.value, log)
        } else {
          log.warn(s"Cannot find ${reportPath}")
        }
      }
    }

  def report(m2Root: String, xmlFile: File, target: File, log: util.Logger): Unit = {
    val rs = Dependency.resolveJar(m2Root, s"org.beangle.sqlplus:beangle-sqlplus:$BeangleSqlplusVersion")
    if (rs._1) {
      val reportDir = new File(target.getAbsolutePath + "/dbreport/")
      reportDir.mkdirs()
      val targetDir = reportDir.getCanonicalPath
      val pb = new ProcessBuilder("java", "-cp", rs._2, "org.beangle.sqlplus.report.Reporter", xmlFile.getAbsolutePath, targetDir)
      log.debug(pb.command().toString)
      pb.inheritIO()
      val pro = pb.start()
      pro.waitFor()
      log.info(s"DDL report was generated in ${targetDir}")
      Tools.openBrowser(targetDir + "/index.html")
    }
  }

  def diff(base: File, targetBase: File, dependencies: Seq[File], dialect: String,
           oldVersion: String, newVersion: String, log: util.Logger): Unit = {
    val folder = new File(targetBase.getAbsolutePath + "/db/" + dialect + "/migrate")
    folder.mkdirs()
    try {
      val oldDbFile = new File(s"${base.getAbsolutePath}/src/main/resources/db/${dialect}/db-${oldVersion}.xml")
      if (!oldDbFile.exists()) {
        log.warn(s"Cannot find ${oldDbFile.getAbsolutePath}")
        return
      }
      val newDbFile = new File(s"${base.getAbsolutePath}/src/main/resources/db/${dialect}/db-${newVersion}.xml")
      if (!newDbFile.exists()) {
        log.warn(s"Cannot find ${newDbFile.getAbsolutePath}")
        return
      }
      val target = folder.getCanonicalPath + s"/${oldVersion}-${newVersion}.sql"
      val classpath = dependencies.map(_.getAbsolutePath).mkString(File.pathSeparator)
      val pb = new ProcessBuilder("java", "-cp", classpath, "org.beangle.jdbc.meta.Diff",
        oldDbFile.getAbsolutePath, newDbFile.getAbsolutePath, target)
      log.debug(pb.command().toString)
      pb.inheritIO()
      val pro = pb.start()
      pro.waitFor()
      log.info("DDl diff generated in " + target)
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }
}
