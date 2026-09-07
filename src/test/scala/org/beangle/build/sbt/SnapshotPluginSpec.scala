package org.beangle.build.sbt

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files

class SnapshotPluginSpec extends AnyFunSpec with Matchers {

  describe("sha1Hex") {
    it("computes lowercase SHA-1 hex digest of file content") {
      val file = File.createTempFile("snapshot", ".bin")
      try {
        Files.write(file.toPath, "hello".getBytes("UTF-8"))
        SnapshotPlugin.sha1Hex(file) shouldBe "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
      } finally {
        file.delete()
      }
    }

    it("handles empty files") {
      val file = File.createTempFile("snapshot", ".empty")
      try {
        SnapshotPlugin.sha1Hex(file) shouldBe "da39a3ee5e6b4b0d3255bfef95601890afd80709"
      } finally {
        file.delete()
      }
    }
  }
}
