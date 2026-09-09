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
 * 手动启用（`trigger = noTrigger`），通常在终端项目（最终应用）显式
 * `enablePlugins(MetaPlugin)`。库项目只负责在自身资源中声明锚点，不再各自生成 idx。
 *
 * 生成是"集中式"的：以整个运行时 classpath（本模块 classes/资源目录 + 依赖项目
 * classes/资源目录 + 外部依赖 jar）为输入，遍历每个条目读取
 * `META-INF/beangle/meta-registrars.txt`（每行一个 MetaRegistrar 类名，`#` 注释）与
 * `beangle.xml`（`<jpa>/<orm><mapping class>` 和 `<cdi><module class>`，均为
 * MetaRegistrar 子类如 MappingModule/BindModule），跨条目合并为注册器契约：每个声明
 * 类必须在 classpath 上找到，否则生成失败。写一份合并的 beanmeta.idx 到本模块
 * `resourceManaged`；[[CompileHookPlugin]] 作为编译后钩子驱动，随终端产物打包。
 *
 * 运行时查找：[[org.beangle.commons.bean.meta.MetaModels]] 读取
 * `classpath*:META-INF/beangle/beanmeta.idx`，集中生成后各模块不再单独携带 idx。
 *
 * 对于 GraalVM native-image 配置，参见 [[AotPlugin]]。
 */
object MetaPlugin extends sbt.AutoPlugin {

  private val OutputPath = "META-INF/beangle/beanmeta.idx"
  private val RegistrarsPath = "META-INF/beangle/meta-registrars.txt"
  private val BeangleXmlName = "beangle.xml"

  object autoImport {
    val metaIndex = taskKey[Option[File]]("Generate consolidated META-INF/beangle/beanmeta.idx from MetaRegistrar classes declared across the runtime classpath")
  }

  import autoImport.*

  override def trigger = noTrigger

  override val projectSettings: Seq[Setting[?]] = Seq(
    Compile / metaIndex := Def.uncached {
      given FileConverter = fileConverter.value
      val classesDir = (Compile / classDirectory).value
      val outDir = (Compile / resourceManaged).value
      val outputPath = outDir / OutputPath
      // post-compile 钩子早于 copyResources，classDirectory 尚未物化本模块资源；
      // 显式包含各条目资源目录（本模块与依赖项目），使声明锚点按运行期 classpath 语义可见。
      val ownResources = (Compile / unmanagedResourceDirectories).value
      val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
      val depResources = (Compile / unmanagedResourceDirectories).all(ScopeFilter(inDependencies(ThisProject))).value
      val external = CpFiles.files((Runtime / externalDependencyClasspath).value)
      val classpath = CpFiles.generatorEntries(classesDir, ownResources, external, depClasses, depResources)
      val registrarTexts = ClasspathScan.readResources(classpath, RegistrarsPath)
      val xmlTexts = ClasspathScan.readResources(classpath, BeangleXmlName)
      val listFile = (Compile / target).value / "meta" / "beanmeta-registrars.txt"
      generate(registrarTexts, xmlTexts, listFile, outputPath, classpath, streams.value.log)
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
      val ownResources = (Test / unmanagedResourceDirectories).value
      val mainClasses = (Compile / classDirectory).value
      val mainResources = (Compile / unmanagedResourceDirectories).value
      val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
      val depResources = (Compile / unmanagedResourceDirectories).all(ScopeFilter(inDependencies(ThisProject))).value
      val external = mainClasses +: (mainResources ++ CpFiles.files((Test / externalDependencyClasspath).value))
      val classpath = CpFiles.generatorEntries(classesDir, ownResources, external, depClasses, depResources)
      val registrarTexts = ClasspathScan.readResources(classpath, RegistrarsPath)
      val xmlTexts = ClasspathScan.readResources(classpath, BeangleXmlName)
      val listFile = (Test / target).value / "meta" / "beanmeta-registrars.txt"
      generate(registrarTexts, xmlTexts, listFile, outputPath, classpath, streams.value.log)
    },
    Test / compilePostHooks += Def.task {
      (Test / metaIndex).value
      ()
    }.taskValue
  )

  /** 合并各 classpath 条目 meta-registrars.txt 与 beangle.xml（jpa/orm mapping、cdi
   * module）声明的 MetaRegistrar 类为契约生成 beanmeta.idx：插件负责解析声明来源，
   * 写入清单文件后交给 MetaGenerator（--registrars）。全部条目皆无声明视为
   * "无 bean 元数据"，正常跳过。
   */
  private def generate(registrarTexts: Seq[String], beangleXmlTexts: Seq[String],
      listFile: File, output: File, classpath: Seq[File], log: Logger): Option[File] = {
    val classNames = collectRegistrars(registrarTexts, beangleXmlTexts)
    if (classNames.isEmpty) {
      log.debug(s"No $RegistrarsPath nor mapping/module in $BeangleXmlName on classpath; beanmeta.idx generation skipped")
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

  /** 合并各 classpath 条目 meta-registrars.txt 与 beangle.xml（mapping/module）声明的
   * MetaRegistrar 类，去重保序。 */
  private[sbt] def collectRegistrars(registrarTexts: Seq[String], beangleXmlTexts: Seq[String]): Seq[String] = {
    val classNames = scala.collection.mutable.LinkedHashSet.empty[String]
    registrarTexts.foreach(text => GeneratorSupport.parseLines(text).foreach(classNames += _))
    GeneratorSupport.extractModuleClasses(beangleXmlTexts).foreach(classNames += _)
    classNames.toSeq
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
