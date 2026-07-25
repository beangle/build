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

import org.beangle.build.style.Style
import org.beangle.build.style.license.*
import org.beangle.build.style.ws.WsOptions
import sbt.Keys.*
import sbt.*

import java.io.{File as JFile}
import scala.collection.mutable

object StylePlugin extends sbt.AutoPlugin {

  object autoImport {
    val licenseRepo = Licenses(this.getClass.getResourceAsStream("/org/beangle/build/style/license/header.md"))

    val styleCheck = taskKey[Unit]("Style check")
    val styleFormat = taskKey[Unit]("Style format")

    val headerEmptyLine: SettingKey[Boolean] =
      settingKey("An empty line should be added between the header and the body")

    lazy val styleTaskSettings: Seq[Def.Setting[?]] = Seq(
      styleCheck := Def.uncached(checkTask.value),
      styleFormat := Def.uncached(formatTask.value)
    )

    lazy val styleCompileSettings: Seq[Def.Setting[?]] = Seq(
      packageBin / packageOptions ++= Def.uncached {
        Seq(PackageOption.ManifestAttributes(new java.util.jar.Attributes.Name("Bundle-License") -> licenseName(licenses.value).getOrElse("UNKNOWN")))
      },
      Compile / compile := Def.uncached((Compile / compile).dependsOn(Compile / styleCheck).value),
      Test / compile := Def.uncached((Test / compile).dependsOn(Test / styleCheck).value)
    )
  }

  import autoImport.*

  override def globalSettings = Seq(headerEmptyLine := true)

  override def trigger = allRequirements

  override val projectSettings =
    inConfig(Compile)(styleTaskSettings) ++
      inConfig(Test)(styleTaskSettings) ++
      styleCompileSettings

  lazy val formatTask =
    Def.task {
      val license = detectLicenseHeader(licenses.value.toList, organizationName.value,
        startYear.value.map(_.toString), licenseRepo)
      val sources = sourceDirs.value
      streams.value.log.info("style formatting for " + name.value)
      Style.format(sources, None, WsOptions.Default, LicenseOptions(license, headerEmptyLine.value))
    }

  lazy val checkTask =
    Def.task {
      val license = detectLicenseHeader(licenses.value.toList, organizationName.value,
        startYear.value.map(_.toString), licenseRepo)
      val sources = sourceDirs.value
      streams.value.log.info("style checking for " + name.value)
      val warns = Style.check(sources, LicenseOptions(license, headerEmptyLine.value))
      if (warns.nonEmpty) {
        throw new MessageOnlyException(
          s"""|Find ${warns.size} files violate style rules.!
              |  ${warns.mkString(s"\n  ")}
              |""".stripMargin
        )
      }
      if (sources.nonEmpty) {
        val base = new JFile(loadedBuild.value.root)
        licenseName(licenses.value) foreach { ln =>
          val copied = Licenses.copyLicense(base, crossTarget.value, licenseRepo, ln)
          if (!copied) streams.value.log.warn(s"Missing license text of ${ln}")
        }
      }
    }

  private def sourceDirs: Def.Initialize[Task[Seq[JFile]]] = Def.task {
    val dirs = new mutable.ArrayBuffer[JFile]
    dirs ++= (Compile / unmanagedSourceDirectories).value
    dirs ++= (Test / unmanagedSourceDirectories).value
    dirs ++= (Compile / unmanagedResourceDirectories).value
    dirs ++= (Test / unmanagedResourceDirectories).value
    dirs.toSeq
  }

  def licenseName(licenses: Seq[sbt.librarymanagement.License]): Option[String] = {
    licenses match {
      case license :: Nil => Some(Utils.licenseSpdxId(license))
      case _ => None
    }
  }

  def detectLicenseHeader(licenses: Seq[sbt.librarymanagement.License], owner: String, startYear: Option[String], repos: Licenses): String = {
    val l = for {
      name <- licenseName(licenses)
      year <- startYear
    } yield repos.header(name, year, owner)
    l.getOrElse("LICENSE NEEDED!!")
  }
}
