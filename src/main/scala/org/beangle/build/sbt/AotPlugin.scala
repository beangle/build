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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import sbt.*
import sbt.Keys.*
import xsbti.FileConverter

import java.io.File

/** Generates GraalVM native-image configuration files from AotHintRegistrar implementations.
  *
  * Opt-in plugin — enable explicitly in projects that define AotHintRegistrar subclasses:
  * {{{
  * // build.sbt
  * lazy val myModule = (project in file(".")).enablePlugins(AotPlugin)
  * }}}
  *
  * Scans the Compile classes directory for [[org.beangle.commons.aot.AotHintRegistrar]]
  * implementations, collects their registrations, and writes GraalVM config files
  * (reflect-config.json, resource-config.json, proxy-config.json, serialization-config.json).
  */
object AotPlugin extends sbt.AutoPlugin {

  private val OutputDir = "META-INF/native-image"

  object autoImport {
    val aotHints = taskKey[Seq[File]]("Generate GraalVM native-image config files from AotHintRegistrar implementations")
  }

  import autoImport.*

  override def trigger = noTrigger

  override val projectSettings: Seq[Setting[?]] = Seq(
    aotHints := Def.uncached {
      given FileConverter = fileConverter.value
      val classesDir = (Compile / classDirectory).value
      val outDir = (Compile / resourceManaged).value / OutputDir
      val classpath = CpFiles.files((Runtime / fullClasspath).value)
      generate(classesDir, outDir, classpath, streams.value.log)
    },
    Compile / resourceGenerators += Def.task {
      aotHints.value
    }.taskValue
  )

  private def generate(classesDir: File, outDir: File, classpath: Seq[File], log: Logger): Seq[File] = {
    if (!classesDir.isDirectory) {
      log.debug(s"Classes directory does not exist: $classesDir")
      return Nil
    }

    val cp = classpath.map(_.getAbsolutePath).mkString(File.pathSeparator)
    try {
      val pb = new ProcessBuilder(
        "java", "-cp", cp,
        "org.beangle.commons.aot.AotHintGenerator",
        "-o", outDir.getAbsolutePath,
        classesDir.getAbsolutePath
      )
      log.debug(pb.command().toString)
      pb.inheritIO()
      val proc = pb.start()
      val exitCode = proc.waitFor()
      if (exitCode == 0) {
        val configNames = Seq("reflect-config.json", "resource-config.json", "proxy-config.json", "serialization-config.json")
        val files = configNames.map(name => outDir / name).filter(_.exists())
        if (files.nonEmpty) {
          log.info(s"Generated GraalVM configs in ${outDir.getAbsolutePath}")
        }
        files
      } else {
        log.warn(s"AotHintGenerator exited with code $exitCode")
        Nil
      }
    } catch {
      case e: Exception =>
        log.error(s"Failed to run AotHintGenerator: ${e.getMessage}")
        Nil
    }
  }
}
