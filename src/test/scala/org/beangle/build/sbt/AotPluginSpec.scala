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

class AotPluginSpec extends AnyFunSpec with Matchers {

  private val mappingXml =
    """<beangle>
      |  <jpa>
      |    <orm>
      |      <mapping class="org.beangle.ems.app.MappingModule"/>
      |    </orm>
      |  </jpa>
      |  <cdi>
      |    <module class="org.beangle.ems.app.CdiModule"/>
      |  </cdi>
      |</beangle>""".stripMargin

  describe("collectRegistrars") {
    it("merges registrar texts and beangle.xml classes from multiple classpath entries, dedup preserving order") {
      val aot = "org.beangle.ems.app.BeangleRegistrar\n"
      val meta =
        """# comment
          |org.beangle.ems.app.CdiModule
          |
          |org.beangle.ems.app.ExtraRegistrar
          |""".stripMargin
      // 第二个 classpath 条目（如另一个依赖 jar 的 beangle.xml）
      val xml2 =
        """<beangle>
          |  <cdi>
          |    <module class="org.beangle.ems.app.CdiModule"/>
          |    <module class="org.beangle.ems.app.OtherRegistrar"/>
          |  </cdi>
          |</beangle>""".stripMargin
      AotPlugin.collectRegistrars(Seq(aot, meta), Seq(mappingXml, xml2)) shouldBe Seq(
        "org.beangle.ems.app.BeangleRegistrar",
        "org.beangle.ems.app.CdiModule",
        "org.beangle.ems.app.ExtraRegistrar",
        "org.beangle.ems.app.MappingModule",
        "org.beangle.ems.app.OtherRegistrar")
    }

    it("returns empty when no anchor declares classes") {
      AotPlugin.collectRegistrars(Seq("# only comments\n"), Seq("<beangle/>")) shouldBe empty
    }

    it("returns empty when no anchors are found on classpath") {
      AotPlugin.collectRegistrars(Seq.empty, Seq.empty) shouldBe empty
    }
  }

  describe("collectClasses") {
    it("extracts web initializer classes from beangle.xml texts of multiple entries") {
      val xml1 =
        """<beangle>
          |  <web>
          |    <initializer class="org.beangle.she.config.ConfigInitializer"/>
          |    <initializer class="org.beangle.she.spring.ContainerInitializer"/>
          |  </web>
          |</beangle>""".stripMargin
      val xml2 =
        """<beangle>
          |  <web>
          |    <initializer class="org.beangle.she.config.ConfigInitializer"/>
          |    <initializer class="org.beangle.she.webmvc.WebmvcInitializer"/>
          |  </web>
          |</beangle>""".stripMargin
      AotPlugin.collectClasses(Seq(xml1, xml2)) shouldBe Seq(
        "org.beangle.she.config.ConfigInitializer",
        "org.beangle.she.spring.ContainerInitializer",
        "org.beangle.she.webmvc.WebmvcInitializer")
    }

    it("returns empty when no beangle.xml is found") {
      AotPlugin.collectClasses(Seq.empty) shouldBe empty
    }
  }

}
