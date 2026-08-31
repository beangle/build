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

import CompileHookPlugin.autoImport.*
import sbt.*
import sbt.Keys.*
import xsbti.FileConverter

import java.io.File

/** Generates `META-INF/beangle/beanmeta.idx` from compiled classes.
 *
 *  Auto-enabled on every JVM project. Generation is driven by the anchor files
 *  `src/main/resources/META-INF/beangle/meta-registrars.txt` (one MetaRegistrar class
 *  name per line, `#` comments allowed) and `src/main/resources/beangle.xml`; projects
 *  with neither are skipped without error, so no explicit opt-in is needed.
 *
 *  Reads both anchors for declared modules (`<jpa>/<orm><mapping class>` and
 *  `<cdi><module class>` in beangle.xml, plus meta-registrars.txt lines, all
 *  MetaRegistrar subclasses such as MappingModule/BindModule) as the contract: every
 *  declared class must be found, otherwise generation fails with an error. Writes a
 *  combined beanmeta.idx into `resourceManaged`; [[CompileHookPlugin]] drives it as a
 *  post-compile hook so it lands in the packaged JAR automatically. Test-scope anchors
 *  are supported via `Test / metaIndex`.
 *
 *  Runtime lookup: [[org.beangle.commons.bean.meta.MetaModels]] reads
 *  `classpath*:META-INF/beangle/beanmeta.idx` at startup.
 *
 *  For GraalVM native-image configuration, see [[AotPlugin]].
 */
object MetaPlugin extends sbt.AutoPlugin {

  private val OutputPath = "META-INF/beangle/beanmeta.idx"
  private val RegistrarsPath = "META-INF/beangle/meta-registrars.txt"
  private val BeangleXmlName = "beangle.xml"

  object autoImport {
    val metaIndex = taskKey[Option[File]]("Generate META-INF/beangle/beanmeta.idx from MetaRegistrar classes declared in beangle.xml or meta-registrars.txt")
  }

  import autoImport.*

  override def trigger = allRequirements

  override val projectSettings: Seq[Setting[?]] = Seq(
    Compile / metaIndex := Def.uncached {
      given FileConverter = fileConverter.value
      val classesDir = (Compile / classDirectory).value
      val outDir = (Compile / resourceManaged).value
      val outputPath = outDir / OutputPath
      val registrarsFile = (Compile / resourceDirectory).value / RegistrarsPath
      val beangleXml = (Compile / resourceDirectory).value / BeangleXmlName
      val listFile = (Compile / target).value / "meta" / "beanmeta-registrars.txt"
      // 本模块 classes 放最前（与运行期 classpath 语义一致），避免依赖项目同名资源遮蔽，见 AotPlugin
      val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
      val classpath = classesDir +: (CpFiles.files((Runtime / externalDependencyClasspath).value) ++ depClasses)
      generate(registrarsFile, beangleXml, listFile, outputPath, classpath, streams.value.log)
    },
    Compile / compilePostHooks += Def.task {
      (Compile / metaIndex).value
      ()
    }.taskValue,
    Test / metaIndex := Def.uncached {
      // 编译时序由 Test / compilePostHooks 保证（compile 完成后执行），这里不能再依赖 compile，否则成环
      given FileConverter = fileConverter.value
      val classesDir = (Test / classDirectory).value
      val outDir = (Test / resourceManaged).value
      val outputPath = outDir / OutputPath
      val registrarsFile = (Test / resourceDirectory).value / RegistrarsPath
      val beangleXml = (Test / resourceDirectory).value / BeangleXmlName
      val listFile = (Test / target).value / "meta" / "beanmeta-registrars.txt"
      val mainClasses = (Compile / classDirectory).value
      val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
      val classpath = classesDir +: (mainClasses +: (CpFiles.files((Test / externalDependencyClasspath).value) ++ depClasses))
      generate(registrarsFile, beangleXml, listFile, outputPath, classpath, streams.value.log)
    },
    Test / compilePostHooks += Def.task {
      (Test / metaIndex).value
      ()
    }.taskValue
  )

  /** 合并 meta-registrars.txt 与 beangle.xml（jpa/orm mapping、cdi module）声明的
   * MetaRegistrar 类为契约生成 beanmeta.idx：插件负责解析声明来源，写入清单文件后
   * 交给 MetaGenerator（--registrars）。两者皆无声明视为"项目无 bean 元数据"，
   * 正常跳过。
   */
  private def generate(registrarsFile: File, beangleXml: File, listFile: File, output: File, classpath: Seq[File], log: Logger): Option[File] = {
    val classNames = collectRegistrars(registrarsFile, beangleXml)
    if (classNames.isEmpty) {
      log.debug(s"No $RegistrarsPath nor mapping/module in $BeangleXmlName; beanmeta.idx generation skipped")
      if (output.exists()) output.delete()
      return None
    }
    writeList(listFile, classNames)
    val cpEntries = classpath.map(_.getAbsolutePath)
    val cp = cpEntries.mkString(File.pathSeparator)
    // post-compile 运行，类基本就绪；退出码 2（声明类未找到）仅作短时重试兜底
    // （sbt 2 的 classDirectory 物化可能晚于编译完成），退出码 1 立即失败。
    GeneratorSupport.retryGenerator(10, 500L, "MetaGenerator", log) {
      runOnce(listFile, output, cp, cpEntries, log)
    }
  }

  /** 合并 meta-registrars.txt 与 beangle.xml（mapping/module）声明的 MetaRegistrar 类，去重保序。 */
  private[sbt] def collectRegistrars(registrarsFile: File, beangleXml: File): Seq[String] = {
    val classNames = scala.collection.mutable.LinkedHashSet.empty[String]
    if (registrarsFile.isFile) readLines(registrarsFile).foreach(classNames += _)
    if (beangleXml.isFile) GeneratorSupport.extractModuleClasses(beangleXml).foreach(classNames += _)
    classNames.toSeq
  }

  /** 读取清单文件：每行一个类名，# 开头为注释，忽略空行。 */
  private def readLines(file: File): Seq[String] = {
    val source = scala.io.Source.fromFile(file, "UTF-8")
    try {
      source.getLines().map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toSeq
    } finally source.close()
  }

  private def writeList(file: File, classNames: Seq[String]): Unit = {
    file.getParentFile.mkdirs()
    val w = new java.io.PrintWriter(file, java.nio.charset.StandardCharsets.UTF_8)
    try classNames.foreach(w.println)
    finally w.close()
  }

  /** Runs the generator once; Right(Some(file)) 成功产出，Right(None) 正常但无产出，Left(GenFailure) 失败。 */
  private def runOnce(listFile: File, output: File, cp: String, cpEntries: Seq[String], log: Logger): Either[GeneratorSupport.GenFailure, Option[File]] = {
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
          Right(Some(output))
        } else {
          log.info(s"No registrars declared; beanmeta.idx generation skipped")
          Right(None)
        }
      } else {
        val output = out.toString
        log.debug(s"MetaGenerator exited with code $exitCode:\n$output")
        Left(GeneratorSupport.GenFailure.of(exitCode, output))
      }
    } catch {
      case e: Exception =>
        log.error(s"Failed to run MetaGenerator: ${e.getMessage}")
        Left(GeneratorSupport.GenFailure(1, s"failed to run: ${e.getMessage}"))
    }
  }
}
