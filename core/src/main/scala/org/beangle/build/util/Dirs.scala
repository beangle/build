package org.beangle.build.util

import java.io.File

object Dirs {
  def on(file: File): Dirs = new Dirs(file)

}

class Dirs(val pwd: File) {
  require(pwd.exists() && pwd.isDirectory)

  def ls(): Seq[String] = pwd.list().toSeq
}
