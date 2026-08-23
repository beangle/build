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

import sbt.*
import sbt.Keys.*
import xsbti.{FileConverter, HashedVirtualFileRef}

import java.io.File as JFile

/** sbt 2 classpath helpers (`HashedVirtualFileRef` → `java.io.File`). */
object CpFiles {
  def file(entry: Attributed[HashedVirtualFileRef])(using conv: FileConverter): JFile =
    conv.toPath(entry.data).toFile

  def files(cp: Seq[Attributed[HashedVirtualFileRef]])(using FileConverter): Seq[JFile] =
    cp.map(file)
}
