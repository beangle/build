package org.beangle.build.sbt

import com.sun.net.httpserver.HttpServer
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.net.{InetSocketAddress, URI}
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

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

  describe("artifactPath") {
    it("lays out maven coordinates as a repository path") {
      SnapshotPlugin.artifactPath("org.beangle.commons", "beangle-commons", "5.0.0-SNAPSHOT") shouldBe
        "org/beangle/commons/beangle-commons/5.0.0-SNAPSHOT"
    }
  }

  describe("snapshotUrl") {
    it("joins the repository base and the relative path with a single slash") {
      SnapshotPlugin.snapshotUrl("http://127.0.0.1:8080/maven/", "org/beangle/commons/x.jar") shouldBe
        "http://127.0.0.1:8080/maven/org/beangle/commons/x.jar"
      SnapshotPlugin.snapshotUrl("http://127.0.0.1:8080/maven", "org/beangle/commons/x.jar") shouldBe
        "http://127.0.0.1:8080/maven/org/beangle/commons/x.jar"
    }
  }

  describe("token") {
    it("reads the token property") {
      val file = credentialsFile("token=s3cret\n")
      try SnapshotPlugin.token(file) shouldBe Some("s3cret")
      finally file.delete()
    }

    it("returns None when the file is missing, empty or has no token") {
      SnapshotPlugin.token(new File("does-not-exist")) shouldBe None
      SnapshotPlugin.token(null) shouldBe None
      val noToken = credentialsFile("user=deploy\npassword=s3cret\n")
      try SnapshotPlugin.token(noToken) shouldBe None
      finally noToken.delete()
    }
  }

  describe("authorization headers") {
    it("sends the token as a Bearer header to the micdn publish endpoint") {
      val file = credentialsFile("token=s3cret\n")
      try SnapshotPlugin.bearerAuthorization(file) shouldBe Some("Bearer s3cret")
      finally file.delete()
    }

    it("returns None when the credentials file is missing") {
      SnapshotPlugin.bearerAuthorization(new File("does-not-exist")) shouldBe None
    }
  }

  describe("upload") {
    it("PUTs the file with the authorization header and returns the status") {
      val request = new AtomicReference[(String, String, String)]
      val received = new AtomicReference[String]
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/maven", exchange => {
        request.set((exchange.getRequestMethod, exchange.getRequestHeaders.getFirst("Authorization"),
          exchange.getRequestURI.getPath))
        received.set(new String(exchange.getRequestBody.readAllBytes(), "UTF-8"))
        exchange.sendResponseHeaders(201, -1)
        exchange.close()
      })
      server.start()
      val file = File.createTempFile("snapshot", ".jar")
      try {
        Files.write(file.toPath, "jar-bytes".getBytes("UTF-8"))
        val url = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/maven/org/example/x.jar").toURL
        val rs = SnapshotPlugin.upload(url, file, "Bearer s3cret")
        rs._1 shouldBe 201
        request.get() shouldBe (("PUT", "Bearer s3cret", "/maven/org/example/x.jar"))
        received.get() shouldBe "jar-bytes"
      } finally {
        file.delete()
        server.stop(0)
      }
    }

    it("returns the error body for a rejected upload instead of throwing") {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/maven", exchange => {
        val bytes = "publish requires a token".getBytes("UTF-8")
        exchange.sendResponseHeaders(401, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      })
      server.start()
      val file = File.createTempFile("snapshot", ".jar")
      try {
        val url = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/maven/org/example/x.jar").toURL
        val rs = SnapshotPlugin.upload(url, file, "Bearer nope")
        rs._1 shouldBe 401
        rs._2 shouldBe "publish requires a token"
      } finally {
        file.delete()
        server.stop(0)
      }
    }
  }

  private def credentialsFile(content: String): File = {
    val file = File.createTempFile("snapshot_credentials", ".properties")
    Files.write(file.toPath, content.getBytes("UTF-8"))
    file
  }
}
