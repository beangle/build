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
import org.beangle.build.util.Bsdiff
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

  describe("installDist") {
    it("installs artifact with its checksums into the native repository layout") {
      val work = Files.createTempDirectory("native-image").toFile
      val repo = NativeImagePlugin.snapshotRoot(new File(work, ".m2"))
      val artifact = write(new File(work, "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.zst"), "payload")

      val installed = NativeImagePlugin.installDist(
        repo, "org.beangle.ems", "beangle-ems-portal", "4.20.14-SNAPSHOT", artifact)

      val dir = new File(repo, "org/beangle/ems/beangle-ems-portal/4.20.14-SNAPSHOT")
      installed shouldBe Seq(
        new File(dir, "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.zst"),
        new File(dir, "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.zst.sha1"),
        new File(dir, "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.zst.sha256"))
      installed.foreach(_.exists shouldBe true)
    }
  }

  describe("repository roots") {
    it("installs releases under <home>/repository and snapshots under <home>/snapshots") {
      val home = new File("/tmp/home/.m2")
      NativeImagePlugin.releaseRoot(home) shouldBe new File(home, "repository")
      NativeImagePlugin.snapshotRoot(home) shouldBe new File(home, "snapshots")
      NativeImagePlugin.repositoryRoot(home, "4.20.14") shouldBe NativeImagePlugin.releaseRoot(home)
      NativeImagePlugin.repositoryRoot(home, "4.20.14-SNAPSHOT") shouldBe NativeImagePlugin.snapshotRoot(home)
    }
  }

  describe("repositoryPath") {
    it("converts group id dots to path separators") {
      NativeImagePlugin.repositoryPath("org.beangle.ems", "beangle-ems-portal", "4.20.14-SNAPSHOT") shouldBe
        "org/beangle/ems/beangle-ems-portal/4.20.14-SNAPSHOT"
    }
  }

  describe("previousLocalVersion") {
    it("picks the greatest released local version lower than the current one, comparing numbers as numbers") {
      val root = Files.createTempDirectory("native-repo").toFile
      val versions = Seq("4.20.9", "4.20.13")
      versions.foreach { v =>
        val dir = new File(root, s"org/beangle/ems/beangle-ems-portal/$v")
        dir.mkdirs()
        write(new File(dir, s"beangle-ems-portal-$v-linux-amd64.tar.gz"), v)
      }
      val snapshot = new File(root, "org/beangle/ems/beangle-ems-portal/4.20.14-SNAPSHOT")
      snapshot.mkdirs()
      write(new File(snapshot, "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.gz"), "snapshot")

      NativeImagePlugin.localVersions(root, "org.beangle.ems", "beangle-ems-portal", "linux-amd64").toSet shouldBe
        versions.toSet
      NativeImagePlugin.previousLocalVersion(
        root, "org.beangle.ems", "beangle-ems-portal", "4.20.14-SNAPSHOT", "linux-amd64") shouldBe Some("4.20.13")
      NativeImagePlugin.previousLocalVersion(
        root, "org.beangle.ems", "beangle-ems-portal", "4.20.16", "linux-amd64") shouldBe Some("4.20.13")
      NativeImagePlugin.previousLocalVersion(
        root, "org.beangle.ems", "beangle-ems-portal", "4.20.9", "linux-amd64") shouldBe None
    }

    it("ignores snapshot versions, even when they are the only ones lower than the current version") {
      val root = Files.createTempDirectory("native-repo").toFile
      Seq("4.20.13", "4.20.14-SNAPSHOT", "4.20.15-SNAPSHOT").foreach { v =>
        val dir = new File(root, s"org/beangle/ems/beangle-ems-portal/$v")
        dir.mkdirs()
        write(new File(dir, s"beangle-ems-portal-$v-linux-amd64.tar.gz"), v)
      }

      NativeImagePlugin.localVersions(root, "org.beangle.ems", "beangle-ems-portal", "linux-amd64") shouldBe Seq("4.20.13")
      NativeImagePlugin.previousLocalVersion(
        root, "org.beangle.ems", "beangle-ems-portal", "4.20.14", "linux-amd64") shouldBe Some("4.20.13")
      NativeImagePlugin.previousLocalVersion(
        root, "org.beangle.ems", "beangle-ems-portal", "4.20.16-SNAPSHOT", "linux-amd64") shouldBe Some("4.20.13")
    }

    it("returns None when only snapshot versions are present") {
      val root = Files.createTempDirectory("native-repo").toFile
      val dir = new File(root, "org/beangle/ems/beangle-ems-portal/4.20.14-SNAPSHOT")
      dir.mkdirs()
      write(new File(dir, "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.gz"), "snapshot")

      NativeImagePlugin.localVersions(root, "org.beangle.ems", "beangle-ems-portal", "linux-amd64") shouldBe Nil
      NativeImagePlugin.previousLocalVersion(
        root, "org.beangle.ems", "beangle-ems-portal", "4.20.14-SNAPSHOT", "linux-amd64") shouldBe None
    }

    it("ignores non version directories and versions published without this classifier") {
      val root = Files.createTempDirectory("native-repo").toFile
      val parent = new File(root, "org/beangle/ems/beangle-ems-portal")
      val otherPlatform = new File(parent, "4.20.13")
      otherPlatform.mkdirs()
      write(new File(otherPlatform, "beangle-ems-portal-4.20.13-darwin-arm64.tar.gz"), "other platform")
      val usable = new File(parent, "4.20.12")
      usable.mkdirs()
      write(new File(usable, "beangle-ems-portal-4.20.12-linux-amd64.tar.gz"), "usable")
      new File(parent, "cache").mkdirs()
      new File(parent, ".attic-20260913").mkdirs()

      NativeImagePlugin.localVersions(root, "org.beangle.ems", "beangle-ems-portal", "linux-amd64") shouldBe Seq("4.20.12")
      NativeImagePlugin.previousLocalVersion(
        root, "org.beangle.ems", "beangle-ems-portal", "4.20.14-SNAPSHOT", "linux-amd64") shouldBe Some("4.20.12")
    }

    it("returns None when the version directory does not exist") {
      val root = Files.createTempDirectory("native-repo").toFile
      NativeImagePlugin.previousLocalVersion(
        root, "org.beangle.ems", "beangle-ems-portal", "4.20.14-SNAPSHOT", "linux-amd64") shouldBe None
    }
  }

  describe("bsdiffInFork") {
    it("runs bsdiff in a child jvm and the patch rebuilds the new file") {
      val dir = Files.createTempDirectory("bsdiff").toFile
      val old = write(new File(dir, "old.txt"), "hello world\n" * 500)
      val current = write(new File(dir, "new.txt"), "hello beangle\n" * 500)
      val patch = new File(dir, "old-new.diff")

      NativeImagePlugin.bsdiffInFork(old, current, patch, "512m") shouldBe true
      patch.length() should be > 0L

      val rebuilt = new File(dir, "rebuilt.txt")
      Bsdiff.patch(old, rebuilt, patch)
      Files.readAllBytes(rebuilt.toPath) shouldBe Files.readAllBytes(current.toPath)
    }

    it("reports false instead of throwing when the child jvm fails") {
      val dir = Files.createTempDirectory("bsdiff").toFile
      val missing = new File(dir, "missing.txt")
      NativeImagePlugin.bsdiffInFork(missing, missing, new File(dir, "x.diff"), "512m") shouldBe false
    }
  }

  describe("deltaName") {
    it("stamps the current snapshot version with its utc build number") {
      NativeImagePlugin.deltaName(
        "beangle-ems-portal", "4.20.13", "4.20.14-SNAPSHOT", Some("20260913.101500-1"), "linux-amd64") shouldBe
        "beangle-ems-portal-4.20.13_4.20.14-SNAPSHOT-20260913.101500-1-linux-amd64.tar.gz.diff"
    }

    it("keeps the maven style name for a released version") {
      NativeImagePlugin.deltaName(
        "beangle-ems-portal", "4.20.13", "4.20.14", None, "linux-amd64") shouldBe
        "beangle-ems-portal-4.20.13_4.20.14-linux-amd64.tar.gz.diff"
    }
  }

  describe("repository urls") {
    it("fills the publish url template and derives the download url") {
      val path = "org/beangle/ems/beangle-ems-portal/4.20.14-SNAPSHOT"
      val file = "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.gz"
      val publishUrl = "https://sas.openurp.net/sas/repo/native/upload/{path}/{fileName}"
      NativeImagePlugin.fill(publishUrl, path, file) shouldBe
        s"https://sas.openurp.net/sas/repo/native/upload/$path/$file"
      NativeImagePlugin.downloadUrl(publishUrl, path, file) shouldBe
        s"https://sas.openurp.net/sas/repo/native/$path/$file"
    }
  }

  describe("distFiles") {
    it("collects the archive and the checksums that exist") {
      val dir = Files.createTempDirectory("native-image").toFile
      val dist = write(new File(dir, "app-1.0-linux-amd64.tar.gz"), "archive")
      NativeImagePlugin.writeSha1(dist)

      NativeImagePlugin.distFiles(dist).map(_.getName) shouldBe Seq(
        "app-1.0-linux-amd64.tar.gz", "app-1.0-linux-amd64.tar.gz.sha1")

      NativeImagePlugin.writeChecksum(dist)
      NativeImagePlugin.distFiles(dist).map(_.getName) shouldBe Seq(
        "app-1.0-linux-amd64.tar.gz", "app-1.0-linux-amd64.tar.gz.sha1", "app-1.0-linux-amd64.tar.gz.sha256")
    }
  }

  describe("findLatestDelta") {
    it("picks the delta with the greatest utc build number") {
      val dir = Files.createTempDirectory("native-repo").toFile
      val older = write(new File(dir, "beangle-ems-portal-4.20.13_4.20.14-SNAPSHOT-20260912.174300-1-linux-amd64.tar.gz.diff"), "old")
      val newer = write(new File(dir, "beangle-ems-portal-4.20.13_4.20.14-SNAPSHOT-20260913.101500-1-linux-amd64.tar.gz.diff"), "new")
      write(new File(dir, "beangle-ems-portal-4.20.13_4.20.15-SNAPSHOT-20260914.101500-1-linux-amd64.tar.gz.diff"), "other version")
      write(new File(dir, "beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.gz"), "archive")

      NativeImagePlugin.findLatestDelta(dir, "beangle-ems-portal", "4.20.14-SNAPSHOT", "linux-amd64") shouldBe Some(newer)
      older.exists() shouldBe true
    }

    it("ignores empty deltas left behind by an interrupted diff") {
      val dir = Files.createTempDirectory("native-repo").toFile
      val broken = write(new File(dir, "beangle-ems-portal-4.20.13_4.20.14-SNAPSHOT-20260913.102219-1-linux-amd64.tar.gz.diff"), "")
      NativeImagePlugin.findLatestDelta(dir, "beangle-ems-portal", "4.20.14-SNAPSHOT", "linux-amd64") shouldBe None

      val good = write(new File(dir, "beangle-ems-portal-4.20.13_4.20.14-SNAPSHOT-20260913.101500-1-linux-amd64.tar.gz.diff"), "delta")
      NativeImagePlugin.findLatestDelta(dir, "beangle-ems-portal", "4.20.14-SNAPSHOT", "linux-amd64") shouldBe Some(good)
      broken.exists() shouldBe true
    }

    it("uses maven style names for a released version and returns None when absent") {
      val dir = Files.createTempDirectory("native-repo").toFile
      val delta = write(new File(dir, "beangle-ems-portal-4.20.13_4.20.14-linux-amd64.tar.gz.diff"), "delta")
      NativeImagePlugin.findLatestDelta(dir, "beangle-ems-portal", "4.20.14", "linux-amd64") shouldBe Some(delta)
      NativeImagePlugin.findLatestDelta(dir, "beangle-ems-portal", "4.20.16", "linux-amd64") shouldBe None
      NativeImagePlugin.deltaBuildNumber(delta.getName, "_4.20.14", "-linux-amd64.tar.gz.diff") shouldBe ""
      NativeImagePlugin.deltaBuildNumber(
        "beangle-ems-portal-4.20.13_4.20.14-SNAPSHOT-20260913.101500-1-linux-amd64.tar.gz.diff",
        "_4.20.14-SNAPSHOT", "-linux-amd64.tar.gz.diff") shouldBe "-20260913.101500-1"
    }
  }
}
