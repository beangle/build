package org.beangle.build.sbt

import org.beangle.build.util.{Files, Https, IOs, Strings}
import sbt.*
import sbt.Def.taskKey
import sbt.Keys.*
import sbt.io.IO

import java.io.{ByteArrayOutputStream, File, FileInputStream}
import java.net.{HttpURLConnection, URI}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import scala.jdk.CollectionConverters.propertiesAsScalaMapConverter

/** 开发快照版支持
 */
object SnapshotPlugin extends sbt.AutoPlugin {

  object autoImport {
    lazy val snapshotBuild = taskKey[File]("Build snapshot war with timestamp")
    lazy val snapshotUpload = taskKey[Unit]("Upload snapshot war to repo")
    lazy val snapshotCredentials = taskKey[File]("Credential to snapshot repo")
    lazy val snapshotRepoUrl: SettingKey[String] = settingKey("Snapshot repository url.")
  }

  import autoImport.*

  override def trigger = allRequirements

  override def projectSettings: Seq[Setting[_]] = {
    Seq(
      snapshotBuild := buildTask.value,
      snapshotUpload := uploadTask.value,
      snapshotCredentials := Path.userHome / ".sbt" / "snapshot_credentials",
      snapshotRepoUrl := "unknown-url"
    )
  }

  private def buildTask = {
    Def.task {
      val log = streams.value.log
      val a = (Compile / Keys.`package` / artifact).value
      val dir = (snapshotBuild / target).value.getAbsolutePath + "/"
      val file = new File(dir + a.name + "-" + version.value + "." + a.extension)
      if (version.value.contains("SNAPSHOT") && (a.extension == "war" || a.extension == ".jar")) {
        if (file.exists()) {
          val formater = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")
          val buildNumber = formater.format(LocalDateTime.now) + "-1"
          val build = new File(dir + a.name + "-" + version.value.replace("-SNAPSHOT", "") + "-" + buildNumber + "." + a.extension)
          if (build.exists()) {
            build.delete()
          }
          Files.copy(file, build)
          log.info(s"Build ${build.getAbsolutePath}")
          build
        } else {
          log.warn(s"Cannot find ${file.getName},build snapshot is aborted.")
          null
        }
      } else {
        log.warn(s"Only supports war/jar with SNAPSHOT version.")
        null
      }
    }
  }

  private def uploadTask = {
    Def.task {
      val log = streams.value.log
      var url = snapshotRepoUrl.value
      val credentials = snapshotCredentials.value
      val file = buildTask.value

      if (url == "unknown-url") {
        log.error(s"set snapshotRepoUrl := http://server/path/to/upload first.")
      } else if (null != file) {
        var user: String = null
        var password: String = null
        if (null != credentials) {
          val properties = new java.util.Properties
          IO.load(properties, credentials)
          val cp = properties.asScala.map { case (k, v) => (k, v.trim) }.toMap

          if (!cp.contains("user") || !cp.contains("password")) {
            log.warn(s"Cannot find user or password from properties file ${credentials}")
          } else {
            user = cp("user")
            password = cp("password")
          }
        }
        url = Strings.replace(url, "{fileName}", file.getName)
        log.info(s"Uploading to ${url}")

        val rs = upload(URI.create(url).toURL, file, user, password)
        if (rs._1 == 200) {
          log.info("Upload success")
        } else {
          log.error(s"Upload Failed for status is ${rs._1} and reason is ${rs._2}")
        }
      }
    }
  }

  private def upload(url: URL, file: File, user: String, password: String): (Int, Any) = {
    val conn = url.openConnection.asInstanceOf[HttpURLConnection]
    Https.noverify(conn)
    conn.setDoOutput(true)
    conn.setConnectTimeout(10 * 1000)
    conn.setReadTimeout(10 * 1000)
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/zip")

    if (null != user) {
      conn.addRequestProperty("Authorization", "Basic " + Base64.getEncoder.encodeToString(s"${user}:${password}".getBytes))
    }
    val os = conn.getOutputStream
    IOs.copy(new FileInputStream(file), os)
    os.close() //don't forget to close the OutputStream
    try {
      conn.connect()
      val bos = new ByteArrayOutputStream
      IOs.copy(conn.getInputStream, bos)
      (conn.getResponseCode, bos.toByteArray)
    } catch {
      case e: Exception =>
        println("Cannot open url " + url + " " + e.getMessage)
        e.printStackTrace()
        (404, e.getMessage)
    } finally
      if (null != conn) conn.disconnect()
  }
}
