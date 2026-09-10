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
}
