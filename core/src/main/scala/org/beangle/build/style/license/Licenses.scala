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

package org.beangle.build.style.license

import org.beangle.build.util.Strings

import java.io.{File, FileWriter, InputStream}
import scala.collection.mutable
import scala.io.{Codec, Source}

object Licenses {
  def apply(input: InputStream): Licenses = {
    val templates = new mutable.HashMap[String, License]

    val content =
      try scala.io.Source.fromInputStream(input)(Codec.UTF8).mkString
      finally input.close()
    val licenses = Strings.split(content, '#')
    licenses foreach { license =>
      var name = license.substring(0, license.indexOf('\n'))
      val body = license.substring(name.length).trim
      if (name.contains("(")) {
        val shortName = Strings.substringBetween(name, "(", ")")
        name = Strings.substringBefore(name, "(")
        val license = License(shortName, name, body)
        templates.put(name.trim, license)
        templates.put(shortName.trim, license)
      } else {
        val license = License(name.trim, name.trim, body)
        templates.put(name.trim, license)
      }
    }
    new Licenses(templates.toMap)
  }

  def copyLicense(base: File, targetDir: File, repo: Licenses, licenseName: String): Boolean = {
    val baseDirectory = base.getAbsolutePath
    // copy license text
    var copied = copy(new File(baseDirectory + "/LICENSE"), targetDir)
    if (!copied) copied = copy(new File(baseDirectory + "/LICENSE.txt"), targetDir)
    if (!copied) {
      repo.get(licenseName) foreach { l =>
        val li = this.getClass.getResourceAsStream(s"/org/beangle/style/license/${l.shortName}.txt")
        if (null != li) copied = copy(li, targetDir)
      }
    }
    copied
  }

  private def copy(li: InputStream, targetDir: File): Boolean = {
    val target = new File(targetDir.getAbsolutePath + "/classes/META-INF")
    if (!target.exists()) target.mkdirs()
    val fw = new FileWriter(target.getAbsolutePath + "/LICENSE")
    fw.write(Source.fromInputStream(li, "UTF-8").mkString)
    fw.close()
    true
  }

  private def copy(licenseFile: File, targetDir: File): Boolean = {
    if (licenseFile.exists()) {
      val target = new File(targetDir.getAbsolutePath + "/classes/META-INF")
      if (!target.exists()) target.mkdirs()
      val fw = new FileWriter(target.getAbsolutePath + "/LICENSE")
      fw.write(Source.fromFile(licenseFile, "UTF-8").mkString)
      fw.close()
      true
    } else false
  }

}

class Licenses(val templates: Map[String, License]) {

  def get(name: String): Option[License] = {
    templates.get(name)
  }

  def header(name: String, year: String, owner: String): String = {
    var content = templates.get(name) match {
      case Some(l) => l.header
      case None => name
    }
    content = Strings.replace(content, "${year}", year)
    content = Strings.replace(content, "${owner}", owner)
    content
  }

}

case class License(shortName: String, fullName: String, header: String)
