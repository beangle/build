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

class AotPluginSpec extends AnyFunSpec with Matchers {

  private def write(file: File, content: String): File = {
    file.getParentFile.mkdirs()
    Files.write(file.toPath, content.getBytes(StandardCharsets.UTF_8))
    file
  }

  describe("collectRegistrars") {
    it("merges aot-registrars.txt, meta-registrars.txt and beangle.xml classes, dedup preserving order") {
      val dir = Files.createTempDirectory("aot").toFile
      val aot = write(dir / "aot-registrars.txt", "org.beangle.ems.app.BeangleRegistrar\n")
      val meta = write(dir / "meta-registrars.txt",
        """# comment
          |org.beangle.ems.app.CdiModule
          |
          |org.beangle.ems.app.ExtraRegistrar
          |""".stripMargin)
      val beangleXml = write(dir / "beangle.xml",
        """<beangle>
          |  <jpa>
          |    <orm>
          |      <mapping class="org.beangle.ems.app.MappingModule"/>
          |    </orm>
          |  </jpa>
          |  <cdi>
          |    <module class="org.beangle.ems.app.CdiModule"/>
          |  </cdi>
          |</beangle>""".stripMargin)
      AotPlugin.collectRegistrars(aot, meta, beangleXml) shouldBe Seq(
        "org.beangle.ems.app.BeangleRegistrar",
        "org.beangle.ems.app.CdiModule",
        "org.beangle.ems.app.ExtraRegistrar",
        "org.beangle.ems.app.MappingModule")
    }

    it("returns empty when no anchor declares classes") {
      val dir = Files.createTempDirectory("aot").toFile
      val aot = write(dir / "aot-registrars.txt", "# only comments\n")
      val meta = write(dir / "meta-registrars.txt", "# only comments\n")
      val beangleXml = write(dir / "beangle.xml", "<beangle/>")
      AotPlugin.collectRegistrars(aot, meta, beangleXml) shouldBe empty
    }

    it("returns empty when all anchors are missing") {
      val dir = Files.createTempDirectory("aot").toFile
      AotPlugin.collectRegistrars(dir / "aot-registrars.txt", dir / "meta-registrars.txt", dir / "beangle.xml") shouldBe empty
    }
  }

  describe("collectClasses") {
    it("extracts web initializer classes from beangle.xml") {
      val dir = Files.createTempDirectory("aot").toFile
      val beangleXml = write(dir / "beangle.xml",
        """<beangle>
          |  <jpa>
          |    <orm>
          |      <mapping class="org.beangle.ems.app.MappingModule"/>
          |    </orm>
          |  </jpa>
          |  <web>
          |    <initializer class="org.beangle.she.config.ConfigInitializer"/>
          |    <initializer class="org.beangle.she.spring.ContainerInitializer"/>
          |  </web>
          |</beangle>""".stripMargin)
      AotPlugin.collectClasses(beangleXml) shouldBe Seq(
        "org.beangle.she.config.ConfigInitializer",
        "org.beangle.she.spring.ContainerInitializer")
    }

    it("returns empty when beangle.xml is missing") {
      val dir = Files.createTempDirectory("aot").toFile
      AotPlugin.collectClasses(dir / "beangle.xml") shouldBe empty
    }
  }
}
