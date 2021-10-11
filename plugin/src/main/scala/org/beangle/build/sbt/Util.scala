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
