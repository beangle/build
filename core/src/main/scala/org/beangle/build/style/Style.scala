/*
 * Copyright © 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.style

import org.beangle.build.style.license.{CommentStyle, LicenseOptions}
import org.beangle.build.style.ws.{DefaultWSFormatter, WsOptions}
import org.beangle.build.util._
import scala.collection.mutable

import java.io.{File, FileInputStream, FileOutputStream}

object Style {

  def format(dirs: Seq[File], ext: Option[String], wsOptions: WsOptions, licenseOptions: LicenseOptions): Unit = {
    dirs foreach { dir =>
      if (dir.exists()) {
        format(dir, ext, wsOptions, licenseOptions)
      }
    }
  }

  def format(dir: File, ext: Option[String], wsOptions: WsOptions, licenseOptions: LicenseOptions): Unit = {
    val builder = DefaultWSFormatter.newBuilder()
    if (wsOptions.tab2space)
      builder.enableTab2space(wsOptions.tablength)
    if (wsOptions.trimTrailing) builder.enableTrimTrailingWhiteSpace()
    if (wsOptions.insertFinalNewline) builder.insertFinalNewline()
    if (wsOptions.fixcrlf) builder.fixcrlf(EOF.LF)

    val wsFormatter = builder.build()
    SourceFileWalker.visit(dir, ext, Set.empty, (file: File) => {
      if (file.canWrite) {
        val content = Files.readString(new FileInputStream(file))
        var rs = wsFormatter.format(content)

        CommentStyle.mappings.get(Files.extension(file)) foreach { cs =>
          val existed = cs.extract(content)
          val newLicense = cs(licenseOptions.license) + (if (licenseOptions.hasSeperator) EOF.LF else "")
          if (existed != newLicense) {
            rs = newLicense + content.substring(existed.length)
          }
        }
        if (rs != content) {
          val fos = new FileOutputStream(file)
          Files.write(rs, fos, Charsets.UTF_8)
          Files.close(fos)
        }
      } else {
        println("Cannot write:"+file.getAbsolutePath)
      }
    })
  }

  def check(dirs: Seq[File], lo: LicenseOptions): collection.Seq[String] = {
    val warns = new mutable.ArrayBuffer[String]
    dirs foreach { dir =>
      if (dir.exists()) {
        check(dir, lo, warns)
      }
    }
    warns
  }

  private def check(dir: File, lo: LicenseOptions, warns: collection.mutable.Buffer[String]): Unit = {
    SourceFileWalker.visit(dir, None, Set.empty, (dir: File) => {
      val content = Files.readString(new FileInputStream(dir))
      val msgs = new mutable.ArrayBuffer[String]
      if (!checkLicense(dir, lo, content)) {
        msgs += "missing license"
      }
      msgs ++= checkWhitespace(content)
      if (msgs.nonEmpty) warns += s"${dir.getAbsolutePath}(${msgs.mkString(",")})"
    })
  }

  private def checkLicense(dir: File, lo: LicenseOptions, content: String): Boolean = {
    CommentStyle.mappings.get(Files.extension(dir)) match {
      case Some(cs) => cs.extract(content) == (cs(lo.license) + (if (lo.hasSeperator) EOF.LF else ""))
      case None => true
    }
  }

  private def checkWhitespace(content: String): Option[String] = {
    if (-1 != content.indexOf("\t") || -1 != content.indexOf(EOF.CRLF)) {
      Some("tab or crlf found")
    } else {
      val lines = Strings.split(content, '\n')
      val i = lines.count(l => l.endsWith(" "))
      if (i > 0) {
        Some(s"$i line has tail empty space")
      } else None
    }
  }

}
