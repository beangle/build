package org.beangle.build.sbt

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import sbt.librarymanagement.{Artifact, ModuleID, ModuleReport}

import java.io.File

class BootPluginSpec extends AnyFunSpec with Matchers {

  describe("requireMavenGav") {
    it("accepts three-part Maven GAV including Scala binary suffix in artifactId") {
      BootPlugin.requireMavenGav("org.scala-lang:scala3-library_3:3.3.8") shouldBe
        "org.scala-lang:scala3-library_3:3.3.8"
      BootPlugin.requireMavenGav("org.beangle.commons:beangle-commons_3:6.2.1") shouldBe
        "org.beangle.commons:beangle-commons_3:6.2.1"
    }

    it("rejects empty parts or non-3-segment coordinates") {
      intercept[IllegalArgumentException] {
        BootPlugin.requireMavenGav("org.scala-lang:scala3-library_3")
      }
      intercept[IllegalArgumentException] {
        BootPlugin.requireMavenGav("org.scala-lang:scala3-library:jar:3.3.8")
      }
      intercept[IllegalArgumentException] {
        BootPlugin.requireMavenGav("org.scala-lang::3.3.8")
      }
    }
  }

  describe("moduleBootGavs") {
    it("emits GAV for inter-project ModuleReport with empty artifacts") {
      val mr = ModuleReport(ModuleID("org.beangle.ems", "beangle-ems-app", "4.20.3"), Vector.empty, Vector.empty)
      BootPlugin.moduleBootGavs(mr) shouldBe Seq("org.beangle.ems:beangle-ems-app:4.20.3")
    }

    it("uses resolved main jar artifactId when present") {
      val jar = new File("/tmp/beangle-commons_3-6.2.1.jar")
      val art = Artifact("beangle-commons_3")
      val mr = ModuleReport(
        ModuleID("org.beangle.commons", "beangle-commons", "6.2.1"),
        Vector(art -> jar),
        Vector.empty
      )
      BootPlugin.moduleBootGavs(mr) shouldBe Seq("org.beangle.commons:beangle-commons_3:6.2.1")
    }

    it("skips modules that only have missing artifacts") {
      val mr = ModuleReport(
        ModuleID("org.example", "missing", "1.0.0"),
        Vector.empty,
        Vector(Artifact("missing"))
      )
      BootPlugin.moduleBootGavs(mr) shouldBe empty
    }
  }

  describe("moduleBootArtifacts") {
    it("attaches exported sibling jar and skips class-directory-only modules") {
      val siblingJar = new File("/tmp/beangle-ems-app-4.20.3.jar")
      val mr = ModuleReport(ModuleID("org.beangle.ems", "beangle-ems-app", "4.20.3"), Vector.empty, Vector.empty)
      val withJar = BootPlugin.moduleBootArtifacts(mr, Map("org.beangle.ems:beangle-ems-app" -> siblingJar))
      withJar.map(_.gav) shouldBe Seq("org.beangle.ems:beangle-ems-app:4.20.3")
      withJar.head.jar shouldBe siblingJar

      BootPlugin.moduleBootArtifacts(mr, Map.empty) shouldBe empty
    }

    it("uses resolved main jar when present") {
      val jar = new File("/tmp/beangle-commons_3-6.2.1.jar")
      val art = Artifact("beangle-commons_3")
      val mr = ModuleReport(
        ModuleID("org.beangle.commons", "beangle-commons", "6.2.1"),
        Vector(art -> jar),
        Vector.empty
      )
      val arts = BootPlugin.moduleBootArtifacts(mr, Map.empty)
      arts.map(_.gav) shouldBe Seq("org.beangle.commons:beangle-commons_3:6.2.1")
      arts.head.jar shouldBe jar
    }
  }
}
