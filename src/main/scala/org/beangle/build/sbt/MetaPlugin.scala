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
 *  Auto-enabled on every JVM project. Generation is driven by the anchor file
 *  `src/main/resources/beangle.xml`; projects without it are skipped without error, so no
 *  explicit opt-in is needed.
 *
 *  Reads `beangle.xml` for declared modules (`<jpa>/<orm><mapping class>` and
 *  `<cdi><module class>`, all MetaRegistrar subclasses such as MappingModule/BindModule)
 *  as the contract: every declared class must be found, otherwise generation fails with an
 *  error. Writes a combined beanmeta.idx, registered via [[resourceGenerators]] so it lands
 *  in the packaged JAR automatically. Test-scope beangle.xml is supported via `Test / metaIndex`.
 *
 *  Runtime lookup: [[org.beangle.commons.bean.meta.MetaModels]] reads
 *  `classpath*:META-INF/beangle/beanmeta.idx` at startup.
 *
 *  For GraalVM native-image configuration, see [[AotPlugin]].
 */
object MetaPlugin extends sbt.AutoPlugin {

  private val OutputPath = "META-INF/beangle/beanmeta.idx"
  private val BeangleXmlName = "beangle.xml"

  object autoImport {
    val metaIndex = taskKey[Option[File]]("Generate META-INF/beangle/beanmeta.idx from modules declared in beangle.xml")
  }

  import autoImport.*

  override def trigger = allRequirements

  override val projectSettings: Seq[Setting[?]] = Seq(
    Compile / metaIndex := Def.uncached {
      given FileConverter = fileConverter.value
      val classesDir = (Compile / classDirectory).value
      val outDir = (Compile / resourceManaged).value
      val outputPath = outDir / OutputPath
      val beangleXml = (Compile / resourceDirectory).value / BeangleXmlName
      val listFile = (Compile / target).value / "meta" / "beanmeta-registrars.txt"
      // 外部依赖 + 依赖项目 classes + 本模块 classes，避免 resourceGenerators 环（见 AotPlugin）
      val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
      val classpath = CpFiles.files((Runtime / externalDependencyClasspath).value) ++ depClasses :+ classesDir
      generate(beangleXml, listFile, classesDir, outputPath, classpath, streams.value.log)
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
      val beangleXml = (Test / resourceDirectory).value / BeangleXmlName
      val listFile = (Test / target).value / "meta" / "beanmeta-registrars.txt"
      val mainClasses = (Compile / classDirectory).value
      val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
      val classpath = CpFiles.files((Test / externalDependencyClasspath).value) ++ depClasses :+ mainClasses :+ classesDir
      generate(beangleXml, listFile, classesDir, outputPath, classpath, streams.value.log)
    },
    Test / resourceGenerators += Def.task {
      (Test / metaIndex).value.toSeq
    }.taskValue
  )

  /** 以 beangle.xml 声明的 mapping/module 类为契约生成 beanmeta.idx：插件负责解析 beangle.xml
   * 决定读取哪些类，写入清单文件后交给 MetaGenerator（--registrars）。
   * beangle.xml 缺失/无声明视为"项目无 bean 元数据"，正常跳过。
   */
  private def generate(beangleXml: File, listFile: File, classesDir: File, output: File, classpath: Seq[File], log: Logger): Option[File] = {
    if (!beangleXml.isFile) {
      log.debug(s"No $BeangleXmlName found; beanmeta.idx generation skipped")
      if (output.exists()) output.delete()
      return None
    }
    val classNames = extractModuleClasses(beangleXml)
    if (classNames.isEmpty) {
      log.debug(s"No mapping/module declared in $beangleXml; beanmeta.idx generation skipped")
      return None
    }
    writeList(listFile, classNames)
    val cpEntries = classpath.map(_.getAbsolutePath)
    val cp = cpEntries.mkString(File.pathSeparator)
    // 与 AotPlugin 同理：resources 与 compileIncremental 并行时声明类可能尚未编译完（非零退出）。
    // 失败时依据 classes 快照区分：快照持续变化 -> 重试；连续三次一致（classes 已稳定）-> 确有缺失类，立即报错。
    val maxAttempts = 8
    val attemptDelayMs = 1500L
    var attempts = 0
    var prevSnapshot = Option.empty[GeneratorSupport.ClassesSnapshot]
    var stableCount = 0
    while (attempts < maxAttempts) {
      val snapshot = GeneratorSupport.snapshotClasses(classesDir)
      runOnce(listFile, output, cp, cpEntries, log) match {
        case Some(fileOpt) => return fileOpt
        case None =>
          if (snapshot.isDefined && prevSnapshot == snapshot) stableCount += 1
          else stableCount = 0
          if (stableCount >= 2) {
            // 契约违约：beangle.xml 声明了模块但 classes 已稳定仍找不到 -> 报错失败构建
            sys.error(s"Modules declared in $BeangleXmlName cannot be loaded (missing or invalid); see warnings above")
          }
      }
      attempts += 1
      prevSnapshot = snapshot
      if (attempts < maxAttempts) {
        log.warn(s"MetaGenerator attempt $attempts failed, retrying...")
        Thread.sleep(attemptDelayMs * attempts)
      }
    }
    log.warn(s"MetaGenerator still failing after $maxAttempts attempts; no beanmeta.idx generated at $output")
    None
  }

  /** 从 beangle.xml 提取声明类：jpa/orm 的 mapping 与 cdi 的 module（带 class 属性）。 */
  private def extractModuleClasses(beangleXml: File): Seq[String] = {
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(beangleXml)
    val classNames = scala.collection.mutable.LinkedHashSet.empty[String]
    def collect(tag: String): Unit = {
      val nodes = doc.getElementsByTagName(tag)
      var i = 0
      while (i < nodes.getLength) {
        val clazz = nodes.item(i).asInstanceOf[org.w3c.dom.Element].getAttribute("class").trim
        if (clazz.nonEmpty) classNames += clazz
        i += 1
      }
    }
    collect("mapping")
    collect("module")
    classNames.toSeq
  }

  private def writeList(file: File, classNames: Seq[String]): Unit = {
    file.getParentFile.mkdirs()
    val w = new java.io.PrintWriter(file, java.nio.charset.StandardCharsets.UTF_8)
    try classNames.foreach(w.println)
    finally w.close()
  }

  /** Runs the generator once; Some(file) 成功产出，Some(None) 正常但无产出，None 失败。 */
  private def runOnce(listFile: File, output: File, cp: String, cpEntries: Seq[String], log: Logger): Option[Option[File]] = {
    try {
      // 清掉残留产物，避免失败/跳过时打包旧 beanmeta.idx
      if (output.exists()) output.delete()
      val cmd = Seq("java", "-cp", cp, "org.beangle.commons.bean.meta.MetaGenerator",
        "--registrars", listFile.getAbsolutePath,
        "-o", output.getAbsolutePath) ++ cpEntries
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
        if (output.exists() && output.length() > 0) {
          log.info(s"Generated beanmeta.idx at ${output.getAbsolutePath}")
          Some(Some(output))
        } else {
          log.info(s"No registrars declared; beanmeta.idx generation skipped")
          Some(None)
        }
      } else {
        log.warn(s"MetaGenerator exited with code $exitCode:\n$out")
        None
      }
    } catch {
      case e: Exception =>
        log.error(s"Failed to run MetaGenerator: ${e.getMessage}")
        None
    }
  }
}
