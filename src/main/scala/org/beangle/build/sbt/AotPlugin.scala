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
  * Auto-enabled on every JVM project. Generation is driven by the anchor file
  * `src/main/resources/META-INF/beangle/aot-registrars.txt` (one
  * [[org.beangle.commons.aot.AotHintRegistrar]] class name per line); projects without it
  * are skipped without error, so no explicit opt-in is needed.
  *
  * Loads each declared registrar, collects its registrations, and writes GraalVM config
  * files (reflect-config.json, resource-config.json, proxy-config.json,
  * serialization-config.json).
  */
object AotPlugin extends sbt.AutoPlugin {

  private val OutputDir = "META-INF/native-image"
  private val RegistrarsPath = "META-INF/beangle/aot-registrars.txt"
  private val ConfigNames = Seq("reflect-config.json", "resource-config.json", "proxy-config.json", "serialization-config.json")

  object autoImport {
    val aotHints = taskKey[Seq[File]]("Generate GraalVM native-image config files from AotHintRegistrar implementations")
  }

  import autoImport.*

  override def trigger = allRequirements

  override val projectSettings: Seq[Setting[?]] = Seq(
    aotHints := Def.uncached {
      given FileConverter = fileConverter.value
      val classesDir = (Compile / classDirectory).value
      val outDir = (Compile / resourceManaged).value / OutputDir
      // 外部依赖 + 依赖项目 classes + 本模块 classes。
      // 不能读 fullClasspath/dependencyClasspath：sbt 2 中它们含本模块 products（exportJars 时为
      // packageBin），resourceGenerators 再依赖它们会形成 resources -> packageBin -> resources 环而卡死。
      val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
      val classpath = CpFiles.files((Runtime / externalDependencyClasspath).value) ++ depClasses :+ classesDir
      generate((Compile / resourceDirectory).value / RegistrarsPath, classesDir, outDir, classpath, streams.value.log)
    },
    Compile / resourceGenerators += Def.task {
      aotHints.value
    }.taskValue
  )

  /** 以 aot-registrars.txt 罗列的 AotHintRegistrar 类为契约生成配置；
   * 清单缺失/为空视为"项目无 AOT 提示"，正常跳过。
   */
  private def generate(registrarsFile: File, classesDir: File, outDir: File, classpath: Seq[File], log: Logger): Seq[File] = {
    if (!registrarsFile.isFile) {
      log.debug(s"No $RegistrarsPath found; GraalVM config generation skipped")
      deleteStaleConfigs(outDir)
      return Nil
    }
    val cpEntries = classpath.map(_.getAbsolutePath)
    val cp = cpEntries.mkString(File.pathSeparator)
    // sbt 2 + exportJars 下 resources 与 compileIncremental 并行调度：编译可能正改写 classes 目录，
    // 清单中的类可能尚未编译完（生成器非零退出）。失败时依据 classes 快照区分：
    //  - 快照持续变化（编译未完成）-> 重试；
    //  - 连续三次快照一致（classes 已稳定）-> 清单中确有缺失/非法类，立即报错。
    val maxAttempts = 8
    val attemptDelayMs = 1500L
    var attempts = 0
    var prevSnapshot = Option.empty[GeneratorSupport.ClassesSnapshot]
    var stableCount = 0
    while (attempts < maxAttempts) {
      val snapshot = GeneratorSupport.snapshotClasses(classesDir)
      runOnce(registrarsFile, outDir, cp, cpEntries, log) match {
        case Some(files) => return files
        case None =>
          if (snapshot.isDefined && prevSnapshot == snapshot) stableCount += 1
          else stableCount = 0
          if (stableCount >= 2) {
            // 契约违约：清单声明了类但 classes 已稳定仍找不到 -> 报错失败构建
            sys.error(s"Declared registrars in $RegistrarsPath cannot be loaded (missing or invalid); see warnings above")
          }
      }
      attempts += 1
      prevSnapshot = snapshot
      if (attempts < maxAttempts) {
        log.warn(s"AotHintGenerator attempt $attempts failed, retrying...")
        Thread.sleep(attemptDelayMs * attempts)
      }
    }
    log.warn(s"AotHintGenerator still failing after $maxAttempts attempts; no GraalVM configs generated in $outDir")
    Nil
  }


  /** 删除输出目录中的残留配置文件。 */
  private def deleteStaleConfigs(outDir: File): Unit = {
    ConfigNames.foreach(name => (outDir / name).delete())
  }
  /** Runs the generator once; returns Some(files) on success, None on failure. */
  private def runOnce(registrarsFile: File, outDir: File, cp: String, cpEntries: Seq[String], log: Logger): Option[Seq[File]] = {
    try {
      // 清掉上次残留配置，避免失败/跳过时把旧产物打包进 jar
      deleteStaleConfigs(outDir)
      val cmd = Seq("java", "-cp", cp, "org.beangle.commons.aot.AotHintGenerator",
        "--registrars", registrarsFile.getAbsolutePath,
        "-o", outDir.getAbsolutePath) ++ cpEntries
      val pb = new ProcessBuilder(cmd.toArray*)
      log.debug(pb.command().toString)
      val out = new StringBuilder
      pb.redirectErrorStream(true)
      val proc = pb.start()
      val reader = new Thread(() => {
        val br = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream))
        var line = br.readLine()
        while (line != null) {
          out.append(line).append('\n')
          line = br.readLine()
        }
      })
      reader.start()
      val exitCode = proc.waitFor()
      reader.join(5000)
      if (exitCode == 0) {
        val files = ConfigNames.map(name => outDir / name).filter(_.exists())
        if (files.nonEmpty) log.info(s"Generated GraalVM configs in ${outDir.getAbsolutePath}")
        Some(files)
      } else {
        log.warn(s"AotHintGenerator exited with code $exitCode:\n$out")
        None
      }
    } catch {
      case e: Exception =>
        log.error(s"Failed to run AotHintGenerator: ${e.getMessage}")
        None
    }
  }
}
