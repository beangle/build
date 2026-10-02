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

import org.beangle.build.util.{Files, Https, IOs}
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
import scala.jdk.CollectionConverters.PropertiesHasAsScala

/** 开发快照版支持：把 SNAPSHOT 的 war/jar 复制成带时间戳的文件名并生成 `.sha1`，
  再以 HTTP PUT 连同 `.sha1` 上传到 micdn 的 `/maven`（`<publish token="…"/>` 声明的发布端点，
  带 `Authorization: Bearer`）。micdn 按仓库相对路径原样落盘，别名 302 与快照元数据
  由它自己的读路径生成，因此这里不再需要 sashub 时代的 `{fileName}` 上传地址。

  凭据只有一种形式：`~/.sbt/snapshot_credentials` 里的 `token`（`Authorization: Bearer`）。
  上传顺序固定为**先构件、后 `.sha1`**：micdn 只做原子落盘，不校验构件与校验文件的先后。
 */
object SnapshotPlugin extends sbt.AutoPlugin {

  object autoImport {
    lazy val snapshotBuild = taskKey[Option[File]]("Build snapshot war with timestamp")
    lazy val snapshotUpload = taskKey[Unit]("Upload snapshot war to repo")
    lazy val snapshotCredentials = taskKey[File]("Credential to snapshot repo")
    lazy val snapshotRepoUrl: SettingKey[String] = settingKey("Micdn /maven repository url.")
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
          val buildNumber = timestampBuildNumber()
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
      val base = snapshotRepoUrl.value
      val credentials = snapshotCredentials.value
      val file = snapshotBuild.value
      val pkg = (Compile / Keys.`package` / artifact).value

      if (base == "unknown-url") {
        log.error(s"set snapshotRepoUrl := http://server/maven first.")
      } else {
        file foreach { f =>
          bearerAuthorization(credentials) match {
            case Some(auth) =>
              // micdn 的 /maven 按仓库相对路径落盘，坐标由构建自己拼（`mvn deploy` 同样如此）
              val dir = artifactPath(organization.value, pkg.name, version.value)
              val sha1File = new File(f.getAbsolutePath + ".sha1")
              if (!sha1File.exists()) {
                log.warn(s"Missing sha1 file ${sha1File.getAbsolutePath}")
              }
              val files = if (sha1File.exists()) Seq(f, sha1File) else Seq(f)
              files foreach { uploadFile =>
                val uploadUrl = snapshotUrl(base, dir + "/" + uploadFile.getName)
                log.info(s"Uploading to ${uploadUrl}")
                val rs = upload(URI.create(uploadUrl).toURL, uploadFile, auth)
                if (rs._1 >= 200 && rs._1 < 300) {
                  log.info(s"Upload ${uploadFile.getName} success")
                } else {
                  log.error(s"Upload ${uploadFile.getName} failed for status is ${rs._1} and reason is ${rs._2}")
                }
              }
            case None =>
              val hint =
                if (null == credentials) "snapshotCredentials is not set"
                else if (!credentials.exists()) s"credentials file ${credentials} does not exist"
                else s"cannot find token in credentials file ${credentials}"
              log.error(s"Snapshot upload is aborted: $hint")
          }
        }
      }
    }
  }

  /** 仓库内相对路径：`groupId` 的点换成斜杠，再按 maven 布局拼 artifactId/version/文件名。 */
  private[sbt] def artifactPath(groupId: String, artifactId: String, version: String): String =
    groupId.split('.').mkString("/") + "/" + artifactId + "/" + version

  private[sbt] def snapshotUrl(base: String, path: String): String =
    base.stripSuffix("/") + "/" + path

  /** 凭据文件里的唯一凭据 `token`，对应 micdn 的 `<publish token="…"/>`。 */
  private[sbt] def token(file: File): Option[String] = {
    if (null == file || !file.exists()) None
    else {
      val properties = new java.util.Properties
      IO.load(properties, file)
      val cp = properties.asScala.map { case (k, v) => (k, v.trim) }.toMap
      cp.get("token").filter(_.nonEmpty)
    }
  }

  /** micdn 发布端点（`/maven`、`/native`）的认证头：`Authorization: Bearer <token>`。 */
  private[sbt] def bearerAuthorization(file: File): Option[String] =
    token(file).map(t => "Bearer " + t)

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

  /** 快照构建号：UTC 时间戳加构建序号，如 `20260913.101500-1`。 */
  private[sbt] def timestampBuildNumber(): String = {
    DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss").format(LocalDateTime.now(ZoneOffset.UTC)) + "-1"
  }

  private[sbt] def upload(url: URL, file: File, authorization: String, method: String = "PUT"): (Int, Any) = {
    val conn = url.openConnection.asInstanceOf[HttpURLConnection]
    Https.noverify(conn)
    conn.setUseCaches(false)
    conn.setRequestProperty("Connection", "close")
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

    conn.setDoOutput(true)
    conn.setDoInput(true)
    conn.setConnectTimeout(10 * 1000)
    conn.setReadTimeout(10 * 1000)
    conn.setRequestMethod(method)
    conn.setRequestProperty("Content-Type", "application/octet-stream")

    if (null != authorization) conn.addRequestProperty("Authorization", authorization)
    try {
      val os = conn.getOutputStream
      IOs.copy(new FileInputStream(file), os)
      os.close()
      val code = conn.getResponseCode
      val bos = new ByteArrayOutputStream
      // 4xx/5xx 时 getInputStream 会抛异常，错误信息只在 errorStream 里
      val stream = if (code >= 400) conn.getErrorStream else conn.getInputStream
      if (null != stream) IOs.copy(stream, bos)
      (code, if (code >= 400) new String(bos.toByteArray) else bos.toByteArray)
    } catch {
      case e: Exception =>
        println("Cannot open url " + url + " " + e.getMessage)
        e.printStackTrace()
        (404, e.getMessage)
    } finally
      if (null != conn) conn.disconnect()
  }
}
