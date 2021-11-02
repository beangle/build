/*
 * Beangle, Agile Development Scaffold and Toolkits.
 *
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

import org.apache.commons.compress.archivers.zip.{ZipArchiveEntry, ZipArchiveOutputStream}
import org.apache.commons.compress.archivers.{ArchiveOutputStream, ArchiveStreamFactory}
import org.beangle.build.util.Files./

import java.io.{File, FileInputStream, FileOutputStream}

object ZipUtils {

  def zip(dir: File, zip: File, encoding: String = "utf-8"): Unit = {
    if (!dir.exists()) {
      println(s"${dir.getAbsolutePath} does not exists,zip process aborted.")
      return
    }
    if (zip.exists()) {
      zip.delete()
    }

    val fos = new FileOutputStream(zip)
    val zos = new ArchiveStreamFactory().createArchiveOutputStream(ArchiveStreamFactory.ZIP, fos)
    if (null != encoding) {
      zos.asInstanceOf[ZipArchiveOutputStream].setEncoding(encoding)
    }
    Dirs.on(dir).ls() foreach { f =>
      addFile(dir, new File(dir.getAbsolutePath + / + f), zos)
    }
    zos.close()
  }

  def addElement(root: File, file: File, zos: ArchiveOutputStream):Unit={
    val entryName = root.toURI.relativize(file.toURI).getPath
    val entry = new ZipArchiveEntry(entryName)
    zos.putArchiveEntry(entry)
    val fis = new FileInputStream(file)
    IOs.copy(fis, zos)
    zos.closeArchiveEntry()
  }

  def addFile(root: File, dir: File, zos: ArchiveOutputStream): Unit = {
    if( dir.isDirectory){
      Dirs.on(dir).ls() foreach { a =>
        val currentFile = new File(dir.getAbsolutePath + / + a)
        var entryName = root.toURI.relativize(currentFile.toURI).getPath
        if (currentFile.isDirectory) {
          if (!entryName.endsWith("/")) { //must be /,not platform dependency \
            entryName = entryName + "/"
          }
          val entry = new ZipArchiveEntry(entryName)
          zos.putArchiveEntry(entry)
          addFile(root, currentFile, zos)
        } else {
          addElement(root,currentFile,zos)
        }
      }
    }else{
      addElement(root,dir,zos)
    }
  }
}
