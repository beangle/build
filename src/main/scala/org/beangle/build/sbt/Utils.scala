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

import sbt.*
import sbt.Keys.*
import sbt.librarymanagement.{Artifact, ModuleID}
import sbt.librarymanagement.LibraryManagementCodec.ArtifactFormat
import sbt.util.CacheStoreFactory
import sbt.util.FilesInfo.{exists, lastModified}
import sjsonnew.support.scalajson.unsafe.{Converter, Parser}
import xsbti.{FileConverter, HashedVirtualFileRef}

import java.io.File as JFile

/** sbt 2 helpers for classpath entries (`HashedVirtualFileRef` + string metadata). */
object Utils {

  def jar(sources: Iterable[(JFile, String)], outputJar: JFile, manifest: java.util.jar.Manifest): Unit =
    IO.jar(sources, outputJar, manifest, None)

  def file(entry: Attributed[HashedVirtualFileRef])(using conv: FileConverter): JFile =
    conv.toPath(entry.data).toFile

  def moduleId(entry: Attributed[?]): Option[ModuleID] =
    entry.metadata.get(moduleIDStr).map(Classpaths.moduleIdJsonKeyFormat.read)

  def artifact(entry: Attributed[?]): Option[Artifact] =
    entry.metadata.get(artifactStr).map { str =>
      Converter.fromJsonUnsafe[Artifact](Parser.parseUnsafe(str))
    }

  def licenseSpdxId(license: sbt.librarymanagement.License): String =
    license.spdxId

  def cacheify(name: String, dest: JFile => Option[JFile], in: Set[JFile], cacheDir: JFile): Set[JFile] = {
    sbt.util.FileFunction
      .cached(CacheStoreFactory(cacheDir / "beangle-war-plugin" / name), lastModified, exists)({ (incs, outcs) =>
        for (removed <- incs.removed; toRemove <- dest(removed)) yield IO.delete(toRemove)
        val newFiles = for (in <- incs.added -- incs.removed; out <- dest(in); _ = IO.copyFile(in, out)) yield out
        val modifieds = for (in <- incs.modified -- incs.removed; out <- dest(in); _ = IO.copyFile(in, out)) yield out
        val missings = for (in <- incs.checked -- incs.removed; out <- dest(in).toSet & outcs.modified; _ = IO.copyFile(in, out)) yield out
        newFiles ++ modifieds ++ missings
      })
      .apply(in)
  }
}
