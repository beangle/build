package org.beangle.build.sbt

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

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
}
