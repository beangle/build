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

  describe("mergeInitializers") {
    it("creates reflect-config.json with public-constructor entries when only initializers exist") {
      val outDir = Files.createTempDirectory("aot-out").toFile
      val files = AotPlugin.mergeInitializers(outDir, Seq("org.beangle.she.config.ConfigInitializer"), sbt.util.Logger.Null)
      val json = new String(Files.readAllBytes((outDir / "reflect-config.json").toPath), StandardCharsets.UTF_8)
      json shouldBe """[{"name":"org.beangle.she.config.ConfigInitializer","allPublicConstructors":true}]"""
      files should contain(outDir / "reflect-config.json")
    }

    it("merges initializer entries into existing reflect-config.json sorted by name") {
      val outDir = Files.createTempDirectory("aot-out").toFile
      outDir.mkdirs()
      val existing = """[{"allPublicMethods":true,"allPublicConstructors":true,"name":"org.beangle.ems.app.MappingModule"}]"""
      Files.write((outDir / "reflect-config.json").toPath, existing.getBytes(StandardCharsets.UTF_8))
      AotPlugin.mergeInitializers(
        outDir,
        Seq("org.beangle.she.config.CleanupInitializer", "org.beangle.she.config.ConfigInitializer"),
        sbt.util.Logger.Null)
      val json = new String(Files.readAllBytes((outDir / "reflect-config.json").toPath), StandardCharsets.UTF_8)
      json shouldBe
        """[{"allPublicMethods":true,"allPublicConstructors":true,"name":"org.beangle.ems.app.MappingModule"},""" +
          """{"name":"org.beangle.she.config.CleanupInitializer","allPublicConstructors":true},""" +
          """{"name":"org.beangle.she.config.ConfigInitializer","allPublicConstructors":true}]"""
    }

    it("does not duplicate an initializer already registered by a registrar") {
      val outDir = Files.createTempDirectory("aot-out").toFile
      outDir.mkdirs()
      val existing = """[{"allPublicMethods":true,"allPublicConstructors":true,"name":"org.beangle.she.config.ConfigInitializer"}]"""
      Files.write((outDir / "reflect-config.json").toPath, existing.getBytes(StandardCharsets.UTF_8))
      AotPlugin.mergeInitializers(outDir, Seq("org.beangle.she.config.ConfigInitializer"), sbt.util.Logger.Null)
      val json = new String(Files.readAllBytes((outDir / "reflect-config.json").toPath), StandardCharsets.UTF_8)
      json shouldBe existing
    }
  }
}
