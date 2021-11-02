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
import org.beangle.build.style.license._
import org.beangle.build.style.ws.WsOptions
import sbt.Keys._
import sbt._

import java.net.URL
import java.util.jar.Attributes
import scala.collection.mutable


object StylePlugin extends sbt.AutoPlugin {

  object autoImport {
    val licenseRepo = Licenses(this.getClass.getResourceAsStream("/org/beangle/build/style/license/header.md"))

    val styleCheck = taskKey[Unit]("Style check")
    val styleFormat = taskKey[Unit]("Style format")

    val headerEmptyLine: SettingKey[Boolean] =
      settingKey("An empty line should be added between the header and the body")

    lazy val styleSettings: Seq[Def.Setting[_]] = Seq(
      styleCheck := checkTask.value,
      styleFormat := formatTask.value,
      packageBin / packageOptions += Package.ManifestAttributes(new java.util.jar.Attributes.Name("Bundle-License") -> licenseName(licenses.value).getOrElse("UNKOWN")),
      compile := compile.dependsOn(autoImport.styleCheck).value
    )
  }

  import autoImport._

  override def globalSettings = Seq(headerEmptyLine := true)

  override def trigger = allRequirements

  // a group of settings that are automatically added to projects.
  override val projectSettings = inConfig(Compile)(styleSettings) ++ inConfig(Test)(styleSettings)

  lazy val formatTask =
    Def.task {
      val log = streams.value.log
      val license = detectLicenseHeader(licenses.value.toList, organizationName.value,
        startYear.value.map(_.toString), licenseRepo)
      val sources = new mutable.ArrayBuffer[File]
      sources ++= (Compile / unmanagedSourceDirectories).value
      sources ++= (Test / unmanagedSourceDirectories).value
      log.info("style formatting for " + name.value)
      Style.format(sources, None, WsOptions.Default, LicenseOptions(license, headerEmptyLine.value))
      (packageBin / packageOptions) += Package.ManifestAttributes(new Attributes.Name("Bundle-License") -> license)
    }

  lazy val checkTask =
    Def.task {
      val license = detectLicenseHeader(licenses.value.toList, organizationName.value,
        startYear.value.map(_.toString), licenseRepo)
      val log = streams.value.log
      val sources = new mutable.ArrayBuffer[File]
      sources ++= (Compile / unmanagedSourceDirectories).value
      sources ++= (Test / unmanagedSourceDirectories).value
      log.info("style checking for " + name.value)
      val warns = Style.check(sources, LicenseOptions(license, headerEmptyLine.value))
      if (warns.nonEmpty) {
        throw new MessageOnlyException(
          s"""|Find ${warns.size} files violate style rules.!
              |  ${warns.mkString(s"\n  ")}
              |""".stripMargin
        )
      }
      if (sources.nonEmpty) {
        //copy license to classes/META-INF
        val base = new File(loadedBuild.value.root)
        licenseName(licenses.value) foreach { ln =>
          val copied = Licenses.copyLicense(base, crossTarget.value, licenseRepo, ln)
          if (!copied) log.warn(s"Missing license text of ${ln}")
        }
      }

    }

  def licenseName(licenses: Seq[(String, URL)]): Option[String] = {
    licenses match {
      case (name, _) :: Nil => Some(name)
      case _ => None
    }
  }

  def detectLicenseHeader(licenses: Seq[(String, URL)], owner: String, startYear: Option[String], repos: Licenses): String = {
    val l = for {
      name <- licenseName(licenses)
      year <- startYear
    } yield repos.header(name, year, owner)
    l.getOrElse("LICENSE NEEDED!!")
  }
}
