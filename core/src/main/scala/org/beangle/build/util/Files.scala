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

import java.io._
import java.nio.channels.FileChannel
import java.nio.charset.Charset

object Files {
  private val defaultBufferSize = 1024 * 4

  private val copyBufferSize = 1024 * 1024 * 30

  private val eof = -1

  val / = File.separator

  def extension(file: File): String = {
    Strings.substringAfterLast(file.getName, ".")
  }

  def readString(input: InputStream, charset: Charset = Charsets.UTF_8): String = {
    try {
      val sw = new StringBuilderWriter(16)
      copy(new InputStreamReader(input, charset), sw)
      sw.toString
    } finally {
      close(input)
    }
  }

  def readLines(input: InputStream, charset: Charset = Charsets.UTF_8): List[String] = {
    readLines(new InputStreamReader(input, charset))
  }

  def readLines(input: Reader): List[String] = {
    val reader = toBufferedReader(input)
    val list = new collection.mutable.ListBuffer[String]
    var line = reader.readLine()
    while (line != null) {
      list += line
      line = reader.readLine()
    }
    close(input)
    list.toList
  }

  private def copy(input: Reader, output: Writer): Long = {
    val buffer = new Array[Char](defaultBufferSize)
    var count = 0
    var n = input.read(buffer)
    while (eof != n) {
      output.write(buffer, 0, n)
      count += n
      n = input.read(buffer)
    }
    close(input)
    count
  }

  private def toBufferedReader(reader: Reader): BufferedReader = {
    if (reader.isInstanceOf[BufferedReader]) reader.asInstanceOf[BufferedReader] else new BufferedReader(reader)
  }

  def write(data: String, output: OutputStream, charset: Charset = null): Unit = {
    if (data != null) {
      if (charset == null)
        output.write(data.getBytes())
      else
        output.write(data.getBytes(charset))
    }
  }

  def close(objs: AutoCloseable*): Unit = {
    objs foreach { obj =>
      try {
        if (obj != null) obj.close()
      } catch {
        case ioe: Exception =>
      }
    }
  }

  def copy(srcFile: File, destFile: File): Unit = {
    assert(null != srcFile)
    assert(null != destFile)
    if (!srcFile.exists) {
      throw new FileNotFoundException("Source '" + srcFile + "' does not exist")
    }
    if (srcFile.isDirectory) {
      throw new IOException("Source '" + srcFile + "' exists but is a directory")
    }
    if (srcFile.getCanonicalPath == destFile.getCanonicalPath) {
      throw new IOException("Source '" + srcFile + "' and destination '" + destFile +
        "' are the same")
    }
    val parentFile = destFile.getParentFile
    if (parentFile != null) {
      if (!parentFile.mkdirs() && !parentFile.isDirectory) {
        throw new IOException("Destination '" + parentFile + "' directory cannot be created")
      }
    }
    if (destFile.exists()) {
      if (destFile.isDirectory) {
        throw new IOException("Destination '" + destFile + "' exists but is a directory")
      }
      if (!destFile.canWrite) throw new IOException("Destination '" + destFile + "' exists but is read-only")
    }
    doCopy(srcFile, destFile, true)
  }

  private def doCopy(srcFile: File, destFile: File, preserveFileDate: Boolean): Unit = {
    var fis: FileInputStream = null
    var fos: FileOutputStream = null
    var input: FileChannel = null
    var output: FileChannel = null
    try {
      fis = new FileInputStream(srcFile)
      fos = new FileOutputStream(destFile)
      input = fis.getChannel
      output = fos.getChannel
      val size = input.size
      var pos = 0L
      var count = 0L
      while (pos < size) {
        count = if (size - pos > copyBufferSize) copyBufferSize else size - pos
        pos += output.transferFrom(input, pos, count)
      }
    } finally {
      IOs.close(output)
      IOs.close(fos)
      IOs.close(input)
      IOs.close(fis)
    }
    if (srcFile.length != destFile.length) {
      throw new IOException(s"Failed to copy full contents from '${srcFile}' to '${destFile}'")
    }
    if (preserveFileDate) {
      destFile.setLastModified(srcFile.lastModified())
    }
  }
}
