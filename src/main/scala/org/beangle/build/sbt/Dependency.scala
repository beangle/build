/*
 * Copyright (C) 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import org.beangle.build.downloader.{DefaultDownloader, Networks}

import java.io.{File, InputStreamReader, LineNumberReader}
import java.net.URL
import scala.collection.mutable

object Dependency {
  val repoBase = "https://maven.aliyun.com/nexus/content/groups/public"

  def m2Path(m2Root: String, group: String, artifactName: String, version: String, packaging: String = ".jar"): String = {
    var m2Base = if (m2Root.startsWith("file:")) m2Root.substring("file:".length) else m2Root
    if (File.separatorChar == '/' || m2Root.startsWith("http")) {
      s"${m2Base}/${group.replace('.', '/')}/${artifactName}/${version}/${artifactName}-${version}${packaging}"
    } else {
      m2Base = m2Base.replace('/', '\\')
      s"${m2Base}\\${group.replace('.', '\\')}\\${artifactName}\\${version}\\${artifactName}-${version}${packaging}"
    }
  }

  def resolveJar(target: String, gav: String): (Boolean, String) = {
    val gavs = Seq(gav)
    val rs = Dependency.download(target, gavs)
    if (rs._2.isEmpty) {
      val nestedUrl = Networks.url("jar:file:" + rs._1.head + "!/META-INF/beangle/dependencies")
      val jars = download(target, resolve(nestedUrl))
      if (jars._2.isEmpty) {
        (true, (rs._1.head :: jars._1.toList).mkString(File.pathSeparator))
      } else {
        println("Missing:" + jars._2.mkString(","))
        (false, null)
      }
    } else {
      (false, null)
    }
  }

  def download(target: String, gavs: Seq[String]): (Seq[String], Seq[String]) = {
    val success = new mutable.ArrayBuffer[String]
    val failed = new mutable.ArrayBuffer[String]
    for (gav <- gavs) {
      val infos = gav.split(":")
      val group = infos(0)
      val artifact = infos(1)
      val version = infos(2)
      val file = new File(m2Path(target, group, artifact, version))
      if (!file.exists()) {
        new DefaultDownloader(Networks.url(m2Path(repoBase, group, artifact, version)), file).start();
      }
      if (file.exists()) {
        success += file.getAbsolutePath
      } else {
        failed += gav
      }
    }
    (success.toSeq, failed.toSeq)
  }

  def resolve(resource: URL): Seq[String] = {
    val archives = new mutable.ArrayBuffer[String]
    if (null == resource) return List.empty
    try {
      val reader = new InputStreamReader(resource.openStream())
      val lr = new LineNumberReader(reader)
      var line = lr.readLine()
      while (line != null) {
        if (line.nonEmpty) archives += line
        line = lr.readLine()
      }
      lr.close()
    } catch {
      case e: Exception => e.printStackTrace()
    }
    archives.toSeq
  }
}
