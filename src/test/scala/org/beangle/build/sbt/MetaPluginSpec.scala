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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import sbt.*

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class MetaPluginSpec extends AnyFunSpec with Matchers {

  private def write(file: File, content: String): File = {
    file.getParentFile.mkdirs()
    Files.write(file.toPath, content.getBytes(StandardCharsets.UTF_8))
    file
  }

  describe("collectRegistrars") {
    it("merges meta-registrars.txt with beangle.xml classes, dedup preserving order") {
      val dir = Files.createTempDirectory("meta").toFile
      val registrars = write(dir / "meta-registrars.txt",
        """# comment
          |org.beangle.ems.app.CdiModule
          |
          |org.beangle.ems.app.ExtraRegistrar
          |""".stripMargin)
      val beangleXml = write(dir / "beangle.xml",
        """<beangle>
          |  <cdi>
          |    <module class="org.beangle.ems.app.CdiModule"/>
          |    <module class="org.beangle.ems.app.MappingModule"/>
          |  </cdi>
          |</beangle>""".stripMargin)
      MetaPlugin.collectRegistrars(registrars, beangleXml) shouldBe Seq(
        "org.beangle.ems.app.CdiModule",
        "org.beangle.ems.app.ExtraRegistrar",
        "org.beangle.ems.app.MappingModule")
    }

    it("returns empty when neither anchor declares classes") {
      val dir = Files.createTempDirectory("meta").toFile
      val registrars = write(dir / "meta-registrars.txt", "# only comments\n")
      val beangleXml = write(dir / "beangle.xml", "<beangle/>")
      MetaPlugin.collectRegistrars(registrars, beangleXml) shouldBe empty
    }

    it("returns empty when both anchors are missing") {
      val dir = Files.createTempDirectory("meta").toFile
      MetaPlugin.collectRegistrars(dir / "meta-registrars.txt", dir / "beangle.xml") shouldBe empty
    }
  }
}
