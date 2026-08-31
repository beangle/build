package org.beangle.build.sbt

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import sbt.*

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class GeneratorSupportSpec extends AnyFunSpec with Matchers {

  private def xmlFile(content: String): File = {
    val f = Files.createTempFile("beangle", ".xml").toFile
    Files.write(f.toPath, content.getBytes(StandardCharsets.UTF_8))
    f
  }

  describe("extractWebInitializerClasses") {
    it("extracts initializer classes from the web module") {
      val f = xmlFile(
        """<beangle>
          |  <web>
          |    <initializer class="org.beangle.she.config.ConfigInitializer"/>
          |    <initializer class="org.beangle.she.spring.ContainerInitializer"/>
          |    <initializer class="org.beangle.she.webmvc.WebmvcInitializer"/>
          |    <initializer class="org.beangle.she.config.CleanupInitializer"/>
          |  </web>
          |</beangle>""".stripMargin)
      GeneratorSupport.extractWebInitializerClasses(f) shouldBe Seq(
        "org.beangle.she.config.ConfigInitializer",
        "org.beangle.she.spring.ContainerInitializer",
        "org.beangle.she.webmvc.WebmvcInitializer",
        "org.beangle.she.config.CleanupInitializer")
    }

    it("ignores initializer elements outside the web module") {
      val f = xmlFile(
        """<beangle>
          |  <cdi>
          |    <initializer class="org.beangle.other.NotWeb"/>
          |  </cdi>
          |  <web>
          |    <initializer class="org.beangle.she.config.ConfigInitializer"/>
          |  </web>
          |</beangle>""".stripMargin)
      GeneratorSupport.extractWebInitializerClasses(f) shouldBe
        Seq("org.beangle.she.config.ConfigInitializer")
    }

    it("returns empty when no web module is declared") {
      val f = xmlFile(
        """<beangle>
          |  <cdi>
          |    <module class="org.beangle.ems.app.CdiModule"/>
          |  </cdi>
          |</beangle>""".stripMargin)
      GeneratorSupport.extractWebInitializerClasses(f) shouldBe empty
    }
  }

  describe("extractModuleClasses") {
    it("does not include web initializer classes") {
      val f = xmlFile(
        """<beangle>
          |  <cdi>
          |    <module class="org.beangle.ems.app.CdiModule"/>
          |  </cdi>
          |  <web>
          |    <initializer class="org.beangle.she.config.ConfigInitializer"/>
          |  </web>
          |</beangle>""".stripMargin)
      GeneratorSupport.extractModuleClasses(f) shouldBe Seq("org.beangle.ems.app.CdiModule")
    }
  }

}
