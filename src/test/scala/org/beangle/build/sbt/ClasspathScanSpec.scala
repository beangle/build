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
import sbt.*
import org.scalatest.matchers.should.Matchers

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ClasspathScanSpec extends AnyFunSpec with Matchers {

  private def write(file: File, content: String): File = {
    file.getParentFile.mkdirs()
    Files.write(file.toPath, content.getBytes(StandardCharsets.UTF_8))
    file
  }

  private def makeJar(target: File, entries: Seq[(String, String)]): File = {
    val out = new ZipOutputStream(new FileOutputStream(target))
    try {
      entries.foreach { case (name, content) =>
        out.putNextEntry(new ZipEntry(name))
        out.write(content.getBytes(StandardCharsets.UTF_8))
        out.closeEntry()
      }
    } finally out.close()
    target
  }

  describe("readResources") {
    it("reads the same anchor from directories and jars in classpath order") {
      val dir = Files.createTempDirectory("cps-dir").toFile
      write(dir / "META-INF/beangle/meta-registrars.txt", "org.beangle.moduleA.Registrar\n")
      val jar = makeJar(Files.createTempFile("cps", ".jar").toFile, Seq(
        "META-INF/beangle/meta-registrars.txt" -> "org.beangle.moduleB.Registrar\n"))

      ClasspathScan.readResources(Seq(dir, jar), "META-INF/beangle/meta-registrars.txt") shouldBe Seq(
        "org.beangle.moduleA.Registrar\n",
        "org.beangle.moduleB.Registrar\n")
    }

    it("returns empty when no entry carries the resource") {
      val dir = Files.createTempDirectory("cps-empty").toFile
      ClasspathScan.readResources(Seq(dir), "META-INF/beangle/meta-registrars.txt") shouldBe empty
    }
  }

}
