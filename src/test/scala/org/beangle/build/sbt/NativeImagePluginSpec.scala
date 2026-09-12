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
import sbt.MessageOnlyException

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.sys.process.*

class NativeImagePluginSpec extends AnyFunSpec with Matchers {

  private def write(file: File, content: String): File = {
    Files.write(file.toPath, content.getBytes(StandardCharsets.UTF_8))
    file
  }

  describe("assertNoDuplicates") {
    it("accepts a classpath whose entries are unique") {
      NativeImagePlugin.assertNoDuplicates(Seq("/a/x.jar", "/b/y.jar"), "classpath")
    }

    it("fails and lists the duplicated entries") {
      val e = intercept[MessageOnlyException] {
        NativeImagePlugin.assertNoDuplicates(Seq("/a/x.jar", "/b/y.jar", "/a/x.jar"), "classpath")
      }
      e.getMessage should include("duplicated classpath entries: /a/x.jar")
      e.getMessage should not include "/b/y.jar"
    }
  }

  describe("readArgsClasspath") {
    it("reads the -cp written into the argument file") {
      val file = write(Files.createTempFile("args", ".txt").toFile,
        "-cp\n/a/x.jar:/b/y.jar\n-H:+AddAllCharsets\nMain\n/out/app\n")
      NativeImagePlugin.readArgsClasspath(file) shouldBe Seq("/a/x.jar", "/b/y.jar")
    }

    it("unquotes classpath entries containing spaces") {
      val cp = Seq("/a/x y.jar", "/b/z.jar").mkString(File.pathSeparator)
      val file = write(Files.createTempFile("args", ".txt").toFile, s"-cp\n${NativeImagePlugin.quoteIfNeeded(cp)}\n")
      NativeImagePlugin.readArgsClasspath(file) shouldBe Seq("/a/x y.jar", "/b/z.jar")
    }

    it("returns empty when there is no -cp") {
      val file = write(Files.createTempFile("args", ".txt").toFile, "-H:+AddAllCharsets\n")
      NativeImagePlugin.readArgsClasspath(file) shouldBe empty
    }
  }

  describe("quoteIfNeeded") {
    it("round trips through unquote") {
      Seq("/a/b.jar", "/a/b c.jar", "/a/\"q\".jar", "/a/back\\slash.jar").foreach { arg =>
        NativeImagePlugin.unquote(NativeImagePlugin.quoteIfNeeded(arg)) shouldBe arg
      }
    }
  }

  describe("platformClassifier") {
    it("maps os and arch to a distribution classifier") {
      NativeImagePlugin.platformClassifier("Linux", "amd64") shouldBe "linux-amd64"
      NativeImagePlugin.platformClassifier("Mac OS X", "aarch64") shouldBe "darwin-arm64"
      NativeImagePlugin.platformClassifier("Windows 11", "x86_64") shouldBe "windows-amd64"
    }
  }

  describe("distEntries") {
    it("keeps the binary and runtime libraries, drops build leftovers") {
      val dir = Files.createTempDirectory("native-image").toFile
      val binary = write(new File(dir, "app"), "bin")
      write(new File(dir, "libjvm.so"), "lib")
      write(new File(dir, "native-image.args"), "args")
      NativeImagePlugin.distEntries(dir, binary).map(_.getName) shouldBe Seq("app", "libjvm.so")
    }
  }

  describe("packageDist") {
    it("archives relative names, keeps the executable bit and writes a checksum") {
      val dir = Files.createTempDirectory("native-image").toFile
      val binary = write(new File(dir, "app"), "bin")
      binary.setExecutable(true)
      write(new File(dir, "libjvm.so"), "lib")

      val out = NativeImagePlugin.packageDist(
        dir, NativeImagePlugin.distEntries(dir, binary), new File(dir.getParentFile, "app-1.0-linux-amd64.tar.gz"))
      Process(Seq("tar", "-tzf", out.getAbsolutePath)).!!.linesIterator.toSet shouldBe Set("app", "libjvm.so")

      val extracted = Files.createTempDirectory("native-dist").toFile
      Process(Seq("tar", "-xzf", out.getAbsolutePath, "-C", extracted.getAbsolutePath)).!
      new File(extracted, "app").canExecute shouldBe true

      val checksum = NativeImagePlugin.writeChecksum(out)
      new String(Files.readAllBytes(checksum.toPath), StandardCharsets.UTF_8) should fullyMatch regex "[0-9a-f]{64}  app-1.0-linux-amd64\\.tar\\.gz\n"
    }
  }

  describe("writeSha1") {
    it("writes a maven style sha1 file") {
      val dir = Files.createTempDirectory("native-image").toFile
      val file = write(new File(dir, "app-1.0-linux-amd64.tar.zst"), "content")
      val sha1 = NativeImagePlugin.writeSha1(file)
      sha1.getName shouldBe "app-1.0-linux-amd64.tar.zst.sha1"
      new String(Files.readAllBytes(sha1.toPath), StandardCharsets.UTF_8) should fullyMatch regex "[0-9a-f]{40}"
    }
  }

  describe("installToMavenLocal") {
    it("installs artifact and pom with sha1 into the maven repository layout") {
      val work = Files.createTempDirectory("native-image").toFile
      val repo = new File(work, "repository")
      val artifact = write(new File(work, "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.zst"), "payload")
      val pom = write(new File(work, "beangle-ems-portal-4.20.14-SNAPSHOT.pom"), "<project/>")

      val installed = NativeImagePlugin.installToMavenLocal(
        repo, "org.beangle.ems", "beangle-ems-portal", "4.20.14-SNAPSHOT", artifact, pom)

      val dir = new File(repo, "org/beangle/ems/beangle-ems-portal/4.20.14-SNAPSHOT")
      installed shouldBe Seq(
        new File(dir, "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.zst"),
        new File(dir, "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.zst.sha1"),
        new File(dir, "beangle-ems-portal-4.20.14-SNAPSHOT.pom"),
        new File(dir, "beangle-ems-portal-4.20.14-SNAPSHOT.pom.sha1"))
      installed.foreach(_.exists shouldBe true)
      new String(Files.readAllBytes(new File(dir, "beangle-ems-portal-4.20.14-SNAPSHOT.pom").toPath), StandardCharsets.UTF_8) shouldBe "<project/>"
    }
  }
}
