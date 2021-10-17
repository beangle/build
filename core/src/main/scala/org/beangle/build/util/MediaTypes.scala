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

package org.beangle.build.util

object MediaTypes {

  def main(args: Array[String]): Unit = {
    println(readMediaTypes())
  }

  private val types: Map[String, MediaType] = readMediaTypes()

  private def readMediaTypes(): Map[String, MediaType] = {
    val me = getClass.getClassLoader
    val url = me.getResource("org/beangle/build/style/text.types")
    if (null == url) return Map.empty
    val buf = new collection.mutable.HashMap[String, MediaType]
    Files.readLines(url.openStream(), Charsets.UTF_8) foreach { line =>
      if (Strings.isNotBlank(line) && !line.startsWith("#")) {
        val mimetypeStr = Strings.substringBetween(line, "=", "exts").trim
        assert(!buf.contains(mimetypeStr), "duplicate mime type:" + mimetypeStr)
        val mimetype = MediaType(mimetypeStr)
        buf.put(mimetypeStr, mimetype)

        val exts = Strings.substringAfter(line, "exts").trim.substring(1)
        if (Strings.isNotBlank(exts)) {
          Strings.split(exts, ',') foreach { ext =>
            val extension = ext.trim
            val exists = buf.get(extension)
            assert(exists.isEmpty, s"exists $extension = " + exists.get + ", the newer is " + mimetype)
            buf.put(extension, mimetype)
          }
        }
      }
    }
    buf.toMap
  }

  def get(ext: String, defaultValue: MediaType): MediaType = {
    types.getOrElse(ext, defaultValue)
  }

  def get(ext: String): Option[MediaType] = {
    types.get(ext)
  }
}

object MediaType {
  def apply(token: String): MediaType = {
    val commaIndex = token.indexOf(";")
    val mimetype = if (commaIndex > -1) token.substring(0, commaIndex).trim else token.trim
    val slashIndex = token.indexOf("/")
    if (-1 == slashIndex) {
      new MediaType(mimetype,"*")
    }
    else {
      new MediaType(mimetype.substring(0, slashIndex), mimetype.substring(slashIndex + 1))
    }
  }
}
class MediaType(val primaryType: String, val subType: String) {
  override def toString: String = {
    s"$primaryType/$subType"
  }
}
