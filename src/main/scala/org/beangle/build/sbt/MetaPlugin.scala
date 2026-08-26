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

/** Generates `META-INF/beangle/beanmeta.idx` from compiled classes.
 *
 *  Opt-in plugin — enable explicitly in projects that define MetaRegistrar subclasses:
 *  {{{
 *  // build.sbt
 *  lazy val myModule = (project in file(".")).enablePlugins(MetaPlugin)
 *  }}}
 *
 *  Scans the Compile classes directory for [[org.beangle.commons.bean.meta.MetaRegistrar]]
 *  subclasses, collects their ClassMeta entries, and writes a combined beanmeta.idx.
 *  The file is registered via [[resourceGenerators]] so it lands in the packaged JAR
 *  automatically. Test-scope MetaRegistrar subclasses are supported via `Test / metaIndex`,
 *  which registers the index on the test classpath (e.g. for `Test / run` and `test`).
 *
 *  Runtime lookup: [[org.beangle.commons.bean.meta.MetaModels]] reads
 *  `classpath*:META-INF/beangle/beanmeta.idx` at startup.
 *
 *  For GraalVM native-image configuration, see [[AotPlugin]].
 */
object MetaPlugin extends sbt.AutoPlugin {

  private val OutputPath = "META-INF/beangle/beanmeta.idx"

  object autoImport {
    val metaIndex = taskKey[Option[File]]("Generate META-INF/beangle/beanmeta.idx from MetaRegistrar subclasses")
  }

  import autoImport.*

  override def trigger = noTrigger

  override val projectSettings: Seq[Setting[?]] = Seq(
    Compile / metaIndex := Def.uncached {
      given FileConverter = fileConverter.value
      val classesDir = (Compile / classDirectory).value
      val outDir = (Compile / resourceManaged).value
      val outputPath = outDir / OutputPath
      val classpath = CpFiles.files((Runtime / fullClasspath).value)
      generate(classesDir, outputPath, classpath, streams.value.log)
    },
    Compile / resourceGenerators += Def.task {
      (Compile / metaIndex).value.toSeq
    }.taskValue,
    Test / metaIndex := Def.uncached {
      (Test / compile).value
      given FileConverter = fileConverter.value
      val classesDir = (Test / classDirectory).value
      val outDir = (Test / resourceManaged).value
      val outputPath = outDir / OutputPath
      val classpath = CpFiles.files((Test / fullClasspath).value)
      generate(classesDir, outputPath, classpath, streams.value.log)
    },
    Test / resourceGenerators += Def.task {
      (Test / metaIndex).value.toSeq
    }.taskValue
  )

  private def generate(classesDir: File, output: File, classpath: Seq[File], log: Logger): Option[File] = {
    if (!classesDir.isDirectory) {
      log.debug(s"Classes directory does not exist: $classesDir")
      return None
    }

    val cp = classpath.map(_.getAbsolutePath).mkString(File.pathSeparator)
    try {
      val pb = new ProcessBuilder(
        "java", "-cp", cp,
        "org.beangle.commons.bean.meta.MetaGenerator",
        "-o", output.getAbsolutePath,
        classesDir.getAbsolutePath
      )
      log.debug(pb.command().toString)
      pb.inheritIO()
      val proc = pb.start()
      val exitCode = proc.waitFor()
      if (exitCode == 0 && output.exists()) {
        log.info(s"Generated beanmeta.idx at ${output.getAbsolutePath}")
        Some(output)
      } else {
        log.warn(s"MetaGenerator exited with code $exitCode")
        None
      }
    } catch {
      case e: Exception =>
        log.error(s"Failed to run MetaGenerator: ${e.getMessage}")
        None
    }
  }
}
