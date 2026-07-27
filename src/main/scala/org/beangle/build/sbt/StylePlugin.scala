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

object StylePlugin extends sbt.AutoPlugin {

  object autoImport {
    val licenseRepo = Licenses(this.getClass.getResourceAsStream("/org/beangle/build/style/license/header.md"))

    val styleCheck = taskKey[Unit]("Style check")
    val styleFormat = taskKey[Unit]("Style format")

    val headerEmptyLine: SettingKey[Boolean] =
      settingKey("An empty line should be added between the header and the body")

    /** Per-configuration style tasks (scan only that configuration's dirs). */
    lazy val styleTaskSettings: Seq[Def.Setting[?]] = Seq(
      styleCheck := Def.uncached(checkInConfig.value),
      styleFormat := Def.uncached(formatInConfig.value)
    )

    lazy val stylePackageSettings: Seq[Def.Setting[?]] = Seq(
      Compile / packageBin / packageOptions ++= Def.uncached {
        Seq(PackageOption.ManifestAttributes(new java.util.jar.Attributes.Name("Bundle-License") -> licenseName(licenses.value).getOrElse("UNKNOWN")))
      }
    )
  }

  import autoImport.*

  override def globalSettings = Seq(headerEmptyLine := true)

  override def trigger = allRequirements

  override val projectSettings =
    inConfig(Compile)(styleTaskSettings) ++
      inConfig(Test)(styleTaskSettings) ++
      stylePackageSettings ++
      // Auto style check before Compile/compile. Only current config dirs are scanned
      // (crossing into Test dirs from Compile deadlocks Test/compile on sbt 2).
      // Avoid Def.uncached(self.dependsOn(...).value) — also deadlocks on sbt 2.
      Seq(
        Compile / compile := Def.uncached {
          (Compile / styleCheck).value
          (Compile / compile).value
        },
        Test / compile := Def.uncached {
          (Test / styleCheck).value
          (Test / compile).value
        }
      )

  private lazy val checkInConfig =
    Def.task {
      val license = detectLicenseHeader(licenses.value.toList, organizationName.value,
        startYear.value.map(_.toString), licenseRepo)
      val sources = unmanagedSourceDirectories.value ++ unmanagedResourceDirectories.value
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

  private lazy val formatInConfig =
    Def.task {
      val license = detectLicenseHeader(licenses.value.toList, organizationName.value,
        startYear.value.map(_.toString), licenseRepo)
      val sources = unmanagedSourceDirectories.value ++ unmanagedResourceDirectories.value
      streams.value.log.info("style formatting for " + name.value)
      Style.format(sources, None, WsOptions.Default, LicenseOptions(license, headerEmptyLine.value))
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
