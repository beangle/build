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

import Files./

import java.io.File

object SourceFileWalker {

  def visit(dir: File, ext: Option[String], excludes: Set[String], f: (File) => Unit): Unit = {
    if (!dir.exists()) return
    if (dir.getName.startsWith(".") || excludes.contains(dir.getName)) {
      return
    }
    if (dir.isFile) {
      ext match {
        case Some(e) => if (dir.getName.endsWith(e)) f(dir)
        case None => if (isText(Files.extension(dir))) f(dir)
      }
    } else {
      dir.list() foreach { childName =>
        visit(new File(dir.getAbsolutePath + / + childName), ext, excludes, f)
      }
    }
  }

  private def isText(fileExt: String): Boolean = {
    MediaTypes.get(fileExt) match {
      case Some(m) => (m.primaryType == "text" || fileExt == "xml" || fileExt == "js")
      case None => false
    }
  }
}
