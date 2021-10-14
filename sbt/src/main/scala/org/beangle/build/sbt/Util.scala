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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import sbt.Keys._
import sbt._
import sbt.util.CacheStoreFactory
import sbt.util.FilesInfo.{exists, lastModified}

object Util {

  def jar(sources: Traversable[(File, String)], outputJar: File, manifest: java.util.jar.Manifest): Unit =
    io.IO.jar(sources, outputJar, manifest, None)

  def cacheify(name: String, dest: File => Option[File], in: Set[File], streams: TaskStreams): Set[File] = {
    sbt.util.FileFunction.
      cached(CacheStoreFactory(streams.cacheDirectory / "beangle-war-plugin" / name), lastModified, exists)({ (incs, outcs) =>
        // toss out removed files
        for (removed <- incs.removed; toRemove <- dest(removed)) yield IO.delete(toRemove)
        // new files
        val newFiles = for (in <- incs.added -- incs.removed; out <- dest(in); _ = IO.copyFile(in, out)) yield out
        // modified files
        val modifieds = for (in <- incs.modified -- incs.removed; out <- dest(in); _ = IO.copyFile(in, out)) yield out
        // missing files
        val missings = for (in <- incs.checked -- incs.removed; out <- dest(in).toSet & outcs.modified; _ = IO.copyFile(in, out)) yield out
        // all files
        newFiles ++ modifieds ++ missings
      })
      .apply(in)
  }
}
