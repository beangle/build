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

import org.beangle.build.util.{Files, Https, IOs, Strings}
import sbt.*
import sbt.Def.taskKey
import sbt.Keys.*
import sbt.io.IO

import java.io.{ByteArrayOutputStream, File, FileInputStream}
import java.net.{HttpURLConnection, URI}
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import scala.jdk.CollectionConverters.PropertiesHasAsScala

/** 开发快照版支持
 */
object SnapshotPlugin extends sbt.AutoPlugin {

  object autoImport {
    lazy val snapshotBuild = taskKey[Option[File]]("Build snapshot war with timestamp")
    lazy val snapshotUpload = taskKey[Unit]("Upload snapshot war to repo")
    lazy val snapshotCredentials = taskKey[File]("Credential to snapshot repo")
    lazy val snapshotRepoUrl: SettingKey[String] = settingKey("Snapshot repository url.")
  }

  import autoImport.*

  override def trigger = allRequirements

  override def projectSettings: Seq[Setting[?]] = {
    Seq(
      snapshotBuild := Def.uncached(buildTask.value),
      snapshotUpload := Def.uncached(uploadTask.value),
      snapshotCredentials := Def.uncached(Path.userHome / ".sbt" / "snapshot_credentials"),
      snapshotRepoUrl := "unknown-url"
    )
  }

  private def buildTask = {
    Def.task {
      val log = streams.value.log
      val a = (Compile / Keys.`package` / artifact).value
      val file = fileConverter.value.toPath((Compile / Keys.`package`).value).toFile
      val dir = (snapshotBuild / target).value.getAbsolutePath + "/"
      if (version.value.contains("SNAPSHOT") && (a.extension == "war" || a.extension == "jar")) {
        if (file.exists()) {
          val formater = DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")
          val buildNumber = formater.format(LocalDateTime.now(ZoneOffset.UTC)) + "-1"
          val build = new File(dir + a.name + "-" + version.value.replace("-SNAPSHOT", "") + "-" + buildNumber + "." + a.extension)
          if (build.exists()) {
            build.delete()
          }
          Files.copy(file, build)
          log.info(s"Build ${build.getAbsolutePath}")
          val sha1File = new File(build.getAbsolutePath + ".sha1")
          IO.write(sha1File, sha1Hex(build))
          log.info(s"Generated ${sha1File.getName}")
          Some(build)
        } else {
          log.warn(s"Cannot find ${file.getName},build snapshot is aborted.")
          None
        }
      } else {
        log.warn(s"Only supports war/jar with SNAPSHOT version.")
        None
      }
    }
  }

  private def uploadTask = {
    Def.task {
      val log = streams.value.log
      val url = snapshotRepoUrl.value
      val credentials = snapshotCredentials.value
      val file = snapshotBuild.value

      if (url == "unknown-url") {
        log.error(s"set snapshotRepoUrl := http://server/path/to/upload first.")
      } else {
        file foreach { f =>
          readCredentials(credentials) match {
            case Some((user, password)) =>
              val sha1File = new File(f.getAbsolutePath + ".sha1")
              if (!sha1File.exists()) {
                log.warn(s"Missing sha1 file ${sha1File.getAbsolutePath}")
              }
              val files = if (sha1File.exists()) Seq(f, sha1File) else Seq(f)
              files foreach { uploadFile =>
                val uploadUrl = Strings.replace(url, "{fileName}", uploadFile.getName)
                log.info(s"Uploading to ${uploadUrl}")
                val rs = upload(URI.create(uploadUrl).toURL, uploadFile, user, password)
                if (rs._1 == 200) {
                  log.info(s"Upload ${uploadFile.getName} success")
                } else {
                  log.error(s"Upload ${uploadFile.getName} failed for status is ${rs._1} and reason is ${rs._2}")
                }
              }
            case None =>
              val hint =
                if (null == credentials) "snapshotCredentials is not set"
                else if (!credentials.exists()) s"credentials file ${credentials} does not exist"
                else s"cannot find user or password in credentials file ${credentials}"
              log.error(s"Snapshot upload is aborted: $hint")
          }
        }
      }
    }
  }

  private def readCredentials(file: File): Option[(String, String)] = {
    if (null == file || !file.exists()) None
    else {
      val properties = new java.util.Properties
      IO.load(properties, file)
      val cp = properties.asScala.map { case (k, v) => (k, v.trim) }.toMap
      for {
        user <- cp.get("user")
        password <- cp.get("password")
        if user.nonEmpty && password.nonEmpty
      } yield (user, password)
    }
  }

  private[sbt] def sha1Hex(file: File): String = {
    val md = MessageDigest.getInstance("SHA-1")
    val in = new FileInputStream(file)
    try {
      val buffer = new Array[Byte](8192)
      var n = in.read(buffer)
      while (n != -1) {
        md.update(buffer, 0, n)
        n = in.read(buffer)
      }
    } finally {
      in.close()
    }
    md.digest().map(b => "%02x".format(b & 0xff)).mkString
  }

  private def upload(url: URL, file: File, user: String, password: String): (Int, Any) = {
    val conn = url.openConnection.asInstanceOf[HttpURLConnection]
    Https.noverify(conn)
    conn.setUseCaches(false)
    conn.setRequestProperty("Connection", "close")
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

    conn.setDoOutput(true)
    conn.setDoInput(true)
    conn.setConnectTimeout(10 * 1000)
    conn.setReadTimeout(10 * 1000)
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", "application/zip")

    if (null != user) {
      conn.addRequestProperty("Authorization", "Basic " + Base64.getEncoder.encodeToString(s"${user}:${password}".getBytes))
    }
    try {
      val os = conn.getOutputStream
      IOs.copy(new FileInputStream(file), os)
      os.close()
      val code = conn.getResponseCode
      val bos = new ByteArrayOutputStream
      IOs.copy(conn.getInputStream, bos)
      (code, bos.toByteArray)
    } catch {
      case e: Exception =>
        println("Cannot open url " + url + " " + e.getMessage)
        e.printStackTrace()
        (404, e.getMessage)
    } finally
      if (null != conn) conn.disconnect()
  }
}
