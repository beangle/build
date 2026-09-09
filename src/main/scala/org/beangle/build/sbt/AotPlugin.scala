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
import java.nio.charset.StandardCharsets

/** Generates GraalVM native-image configuration files from AotHintRegistrar implementations.
  *
  * 手动启用（`trigger = noTrigger`），通常在终端项目（最终 war/native-image 应用）显式
  * `enablePlugins(AotPlugin)`。库项目只负责在自身资源中声明锚点，不再各自生成配置。
  *
  * 生成是"集中式"的：以整个运行时 classpath（本模块 classes/资源目录 + 依赖项目
  * classes/资源目录 + 外部依赖 jar）为输入，遍历每个条目读取：
  *  - `META-INF/beangle/aot-registrars.txt` 与 `META-INF/beangle/meta-registrars.txt`
  *    （每行一个 [[org.beangle.commons.aot.AotHintRegistrar]] 实现类名，`#` 注释）；
 *  - 各条目根部的 `beangle.xml`：`<jpa>/<orm>` mapping 与 `<cdi>` module（均为
 *    registrar 类），以及 `<web><initializer class="..."/>`（按名加载类，经
 *    `--classes` 清单由 AotHintGenerator 以 public 构造器 + Scala object 伴生类注册）；
 *  - 资源 glob：由各库 registrar 经 `registerPattern` 按 glob 语义显式声明（不带
 *    `module` 时在整个 classpath 上匹配），插件不再扫描资源后缀。
  *
  * 全部条目均无声明时删除旧的生成产物并跳过，不报错。
  *
  * 加载每个声明的 registrar，收集注册项后写一份合并的 `reachability-metadata.json`
  * （GraalVM 25+ 格式）到本模块 `META-INF/native-image/beangle/`（随终端产物打包）；
  * 运行期初始化类仍写入 `native-image.properties`（`--initialize-at-run-time` 是构建
  * 参数而非元数据）。
  */
object AotPlugin extends sbt.AutoPlugin {

  private val OutputDir = "META-INF/native-image/beangle"
  private val RegistrarsPath = "META-INF/beangle/aot-registrars.txt"
  private val MetaRegistrarsPath = "META-INF/beangle/meta-registrars.txt"
  private val BeangleXmlName = "beangle.xml"
  private val ReachabilityMetadataFile = "reachability-metadata.json"
  private val NativeImagePropertiesFile = "native-image.properties"
  private val LegacyConfigNames = Seq("reflect-config.json", "resource-config.json", "proxy-config.json", "serialization-config.json")

  object autoImport {
    val aotHints = taskKey[Seq[File]]("Generate consolidated GraalVM native-image config from AotHintRegistrar implementations (aot/meta registrars) and beangle.xml web initializers across the runtime classpath")
  }

  import autoImport.*

  override def trigger = noTrigger

  override val projectSettings: Seq[Setting[?]] = Seq(
    Compile / aotHints := Def.uncached {
      given FileConverter = fileConverter.value
      val classesDir = (Compile / classDirectory).value
      val outDir = (Compile / resourceManaged).value / OutputDir
      // post-compile 钩子早于 copyResources，classDirectory 尚未物化本模块资源；
      // 因此 classpath 显式含各条目资源目录（本模块与依赖项目），与运行期 classpath
      // 语义一致。本模块 classes/资源放最前，避免依赖项目同名资源遮蔽。
      // 不能读 fullClasspath/dependencyClasspath：sbt 2 中它们含本模块 products（exportJars 时为
      // packageBin），resourceGenerators 再依赖它们会形成 resources -> packageBin -> resources 环而卡死。
      val ownResources = (Compile / unmanagedResourceDirectories).value
      val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
      val depResources = (Compile / unmanagedResourceDirectories).all(ScopeFilter(inDependencies(ThisProject))).value
      val external = CpFiles.files((Runtime / externalDependencyClasspath).value)
      val classpath = CpFiles.generatorEntries(classesDir, ownResources, external, depClasses, depResources)
      val registrarTexts =
        ClasspathScan.readResources(classpath, RegistrarsPath) ++
          ClasspathScan.readResources(classpath, MetaRegistrarsPath)
      val xmlTexts = ClasspathScan.readResources(classpath, BeangleXmlName)
      val listFile = (Compile / target).value / "aot" / "aot-registrars.txt"
      val classesFile = (Compile / target).value / "aot" / "aot-classes.txt"
      generate(registrarTexts, xmlTexts, listFile, classesFile, outDir, classpath, streams.value.log)
    },
    Compile / compilePostHooks += Def.task {
      (Compile / aotHints).value
      ()
    }.taskValue
  )

  /** 合并各 classpath 条目声明的 aot/meta registrar 与 beangle.xml（mapping/module）
   * 类为契约，由 AotHintGenerator 统一生成配置；web initializer 等按名加载类经
   * `--classes` 清单传入，不作为 registrar 加载。全部条目皆无声明视为"无 AOT 提示"，
   * 正常跳过。
   */
  private def generate(registrarTexts: Seq[String], xmlTexts: Seq[String], listFile: File,
      classesFile: File, outDir: File, classpath: Seq[File], log: Logger): Seq[File] = {
    val declared = collectRegistrars(registrarTexts, xmlTexts)
    val classes = collectClasses(xmlTexts)
    if (declared.isEmpty && classes.isEmpty) {
      log.debug(s"No $RegistrarsPath/$MetaRegistrarsPath nor modules in $BeangleXmlName on classpath; GraalVM config generation skipped")
      deleteStaleConfigs(outDir)
      return Nil
    }
    writeList(listFile, declared.toSeq)
    writeList(classesFile, classes)
    val cpEntries = classpath.map(_.getAbsolutePath)
    val cp = cpEntries.mkString(File.pathSeparator)
    // post-compile 运行，类基本就绪；退出码 2（声明类未找到）仅作短时重试兜底
    // （sbt 2 的 classDirectory 物化可能晚于编译完成），退出码 1 立即失败。
    GeneratorSupport.retryGenerator(10, 500L, "AotHintGenerator", log) {
      runOnce(listFile, classesFile, outDir, cp, cpEntries, log)
    }
  }

  /** 合并各 classpath 条目 aot/meta-registrars.txt 文本与 beangle.xml（mapping/module）
   * 声明的 AotHintRegistrar 类，去重保序。 */
  private[sbt] def collectRegistrars(registrarTexts: Seq[String], beangleXmlTexts: Seq[String]): Seq[String] = {
    val classNames = scala.collection.mutable.LinkedHashSet.empty[String]
    registrarTexts.foreach(text => GeneratorSupport.parseLines(text).foreach(classNames += _))
    GeneratorSupport.extractModuleClasses(beangleXmlTexts).foreach(classNames += _)
    classNames.toSeq
  }

  /** 从各 classpath 条目的 beangle.xml 提取 web initializer 类
   * （classes 清单，不要求是 AotHintRegistrar）。 */
  private[sbt] def collectClasses(beangleXmlTexts: Seq[String]): Seq[String] =
    GeneratorSupport.extractWebInitializerClasses(beangleXmlTexts)

  private def writeList(file: File, classNames: Seq[String]): Unit = {
    file.getParentFile.mkdirs()
    val w = new java.io.PrintWriter(file, java.nio.charset.StandardCharsets.UTF_8)
    try classNames.foreach(w.println)
    finally w.close()
  }

  /** 删除输出目录中的残留配置文件（含迁移前的扁平目录残留）。 */
  private def deleteStaleConfigs(outDir: File): Unit = {
    // Clean up legacy config files
    LegacyConfigNames.foreach { name =>
      (outDir / name).delete()
      (outDir.getParentFile / name).delete()
    }
    // Clean up new consolidated file
    (outDir / ReachabilityMetadataFile).delete()
    (outDir.getParentFile / ReachabilityMetadataFile).delete()
    // Clean up native-image.properties (may exist in both formats)
    (outDir / NativeImagePropertiesFile).delete()
    (outDir.getParentFile / NativeImagePropertiesFile).delete()
  }

  /** Runs the generator once; Right(files) 成功产出，Left(GenFailure) 按退出码分类失败。 */
  private def runOnce(registrarsFile: File, classesFile: File, outDir: File, cp: String, cpEntries: Seq[String], log: Logger): Either[GeneratorSupport.GenFailure, Seq[File]] = {
    try {
      // 清掉上次残留配置，避免失败/跳过时把旧产物打包进 jar
      deleteStaleConfigs(outDir)
      val cmd = Seq("java", "-cp", cp, "org.beangle.commons.aot.AotHintGenerator",
        "--registrars", registrarsFile.getAbsolutePath,
        "--classes", classesFile.getAbsolutePath,
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
        val files = Seq(outDir / ReachabilityMetadataFile, outDir / NativeImagePropertiesFile).filter(_.exists())
        if (files.nonEmpty) log.info(s"Generated GraalVM reachability-metadata in ${outDir.getAbsolutePath}")
        Right(files)
      } else {
        val output = out.toString
        log.debug(s"AotHintGenerator exited with code $exitCode:\n$output")
        Left(GeneratorSupport.GenFailure.of(exitCode, output))
      }
    } catch {
      case e: Exception =>
        log.error(s"Failed to run AotHintGenerator: ${e.getMessage}")
        Left(GeneratorSupport.GenFailure(1, s"failed to run: ${e.getMessage}"))
    }
  }
}
