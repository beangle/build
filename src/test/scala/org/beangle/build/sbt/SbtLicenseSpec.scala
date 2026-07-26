package org.beangle.build.sbt

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import sbt.librarymanagement.License

import java.net.URI

class SbtLicenseSpec extends AnyFunSpec with Matchers {

  describe("sbt.librarymanagement.License") {
    it("should expose spdx id") {
      val license = License("LGPL-3.0", URI.create("http://www.gnu.org/licenses/lgpl-3.0.txt"))
      Utils.licenseSpdxId(license) shouldBe "LGPL-3.0"
    }
  }
}
