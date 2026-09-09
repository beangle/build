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

class MetaPluginSpec extends AnyFunSpec with Matchers {

  describe("collectRegistrars") {
    it("merges meta-registrars.txt texts with beangle.xml classes of multiple entries, dedup preserving order") {
      val registrars =
        """# comment
          |org.beangle.ems.app.CdiModule
          |
          |org.beangle.ems.app.ExtraRegistrar
          |""".stripMargin
      val xml1 =
        """<beangle>
          |  <cdi>
          |    <module class="org.beangle.ems.app.CdiModule"/>
          |    <module class="org.beangle.ems.app.MappingModule"/>
          |  </cdi>
          |</beangle>""".stripMargin
      val xml2 =
        """<beangle>
          |  <jpa>
          |    <orm>
          |      <mapping class="org.beangle.ems.app.MappingModule"/>
          |    </orm>
          |  </jpa>
          |  <cdi>
          |    <module class="org.beangle.ems.app.OtherRegistrar"/>
          |  </cdi>
          |</beangle>""".stripMargin
      MetaPlugin.collectRegistrars(Seq(registrars), Seq(xml1, xml2)) shouldBe Seq(
        "org.beangle.ems.app.CdiModule",
        "org.beangle.ems.app.ExtraRegistrar",
        "org.beangle.ems.app.MappingModule",
        "org.beangle.ems.app.OtherRegistrar")
    }

    it("returns empty when neither anchor declares classes") {
      MetaPlugin.collectRegistrars(Seq("# only comments\n"), Seq("<beangle/>")) shouldBe empty
    }

    it("returns empty when no anchors are found on classpath") {
      MetaPlugin.collectRegistrars(Seq.empty, Seq.empty) shouldBe empty
    }
  }
}
