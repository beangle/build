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

  describe("GenFailure.of") {
    it("treats missing declared classes as retryable") {
      GeneratorSupport.GenFailure.of(2, "1 of 2 declared MetaRegistrar classes not found").exitCode shouldBe 2
    }

    it("treats a declared Scala object that cannot be instantiated as retryable") {
      val output = """Failed to load declared MetaRegistrar implementations:
                     |org.beangle.data.hibernate.model.TestMapping1 is not a MetaRegistrar""".stripMargin
      GeneratorSupport.GenFailure.of(1, output).exitCode shouldBe 2
    }

    it("keeps other exit-1 failures deterministic") {
      GeneratorSupport.GenFailure.of(1, "unknown option: --foo").exitCode shouldBe 1
    }

    it("summarizes with the last non-empty output line") {
      GeneratorSupport.GenFailure.of(1, "first\n\nlast\n").summary shouldBe "last"
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
