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

/** Generates GraalVM native-image configuration files from AotHintRegistrar implementations.
  *
  * Auto-enabled on every JVM project. Generation is driven by the anchor files
  * `src/main/resources/META-INF/beangle/aot-registrars.txt` and
  * `src/main/resources/META-INF/beangle/meta-registrars.txt` (one
  * [[org.beangle.commons.aot.AotHintRegistrar]] class name per line) plus the modules
  * declared in `src/main/resources/beangle.xml` (`<jpa>/<orm>` mappings and `<cdi>`
  * modules, all AotHintRegistrar subclasses via MetaRegistrar, plus `<web><initializer>`
  * classes which are registered into reflect-config.json by AotHintGenerator — public
  * constructors plus the Scala object companion when the class is actually an object);
  * projects with none of these are skipped without error, so no explicit opt-in is needed.
  *
  * Loads each declared registrar, collects its registrations, and writes GraalVM config
  * files into the `META-INF/native-image/beangle/` subdirectory (reflect-config.json,
  * resource-config.json, proxy-config.json, serialization-config.json,
  * native-image.properties) — each framework/plugin owns its own subdirectory, so
  * configs merge cleanly when multiple libraries are on the classpath.
  */
object AotPlugin extends sbt.AutoPlugin {

  private val OutputDir = "META-INF/native-image/beangle"
  private val RegistrarsPath = "META-INF/beangle/aot-registrars.txt"
  private val MetaRegistrarsPath = "META-INF/beangle/meta-registrars.txt"
  private val BeangleXmlName = "beangle.xml"
  private val ConfigNames = Seq("reflect-config.json", "resource-config.json", "proxy-config.json", "serialization-config.json", "native-image.properties")

  object autoImport {
    val aotHints = taskKey[Seq[File]]("Generate GraalVM native-image config files from AotHintRegistrar implementations (aot/meta registrars) and beangle.xml web initializers")
  }

  import autoImport.*

  override def trigger = allRequirements

  override val projectSettings: Seq[Setting[?]] = Seq(
    aotHints := Def.uncached {
      given FileConverter = fileConverter.value
      val classesDir = (Compile / classDirectory).value
      val outDir = (Compile / resourceManaged).value / OutputDir
      // 本模块 classes + 外部依赖 + 依赖项目 classes（本模块优先，与运行期 classpath 语义一致）。
      // 不能读 fullClasspath/dependencyClasspath：sbt 2 中它们含本模块 products（exportJars 时为
      // packageBin），resourceGenerators 再依赖它们会形成 resources -> packageBin -> resources 环而卡死。
      val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
      val classpath = classesDir +: (CpFiles.files((Runtime / externalDependencyClasspath).value) ++ depClasses)
      val registrarsFile = (Compile / resourceDirectory).value / RegistrarsPath
      val metaRegistrarsFile = (Compile / resourceDirectory).value / MetaRegistrarsPath
      val beangleXml = (Compile / resourceDirectory).value / BeangleXmlName
      val listFile = (Compile / target).value / "aot" / "aot-registrars.txt"
      val classesFile = (Compile / target).value / "aot" / "aot-classes.txt"
      generate(registrarsFile, metaRegistrarsFile, beangleXml, listFile, classesFile, outDir, classpath, streams.value.log)
    },
    Compile / compilePostHooks += Def.task {
      (Compile / aotHints).value
      ()
    }.taskValue
  )

  /** 合并 aot-registrars.txt、meta-registrars.txt 与 beangle.xml（jpa/orm mapping、cdi
   * module）声明的 AotHintRegistrar 类、beangle.xml 的 web initializer 类为契约，由
   * AotHintGenerator 统一生成配置：initializer 等按名加载类经 --classes 清单传入，
   * 不作为 registrar 加载，生成器注册 public 构造器 + Scala object 伴生类。三者皆无
   * 声明视为"项目无 AOT 提示"，正常跳过。
   */
  private def generate(registrarsFile: File, metaRegistrarsFile: File, beangleXml: File, listFile: File, classesFile: File, outDir: File, classpath: Seq[File], log: Logger): Seq[File] = {
    val declared = collectRegistrars(registrarsFile, metaRegistrarsFile, beangleXml)
    val classes = collectClasses(beangleXml)
    if (declared.isEmpty && classes.isEmpty) {
      log.debug(s"No $RegistrarsPath/$MetaRegistrarsPath nor modules in $BeangleXmlName; GraalVM config generation skipped")
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

  /** 合并 aot-registrars.txt、meta-registrars.txt 与 beangle.xml（mapping/module）声明的
   * AotHintRegistrar 类，去重保序。 */
  private[sbt] def collectRegistrars(aotRegistrarsFile: File, metaRegistrarsFile: File, beangleXml: File): Seq[String] = {
    val classNames = scala.collection.mutable.LinkedHashSet.empty[String]
    if (aotRegistrarsFile.isFile) readLines(aotRegistrarsFile).foreach(classNames += _)
    if (metaRegistrarsFile.isFile) readLines(metaRegistrarsFile).foreach(classNames += _)
    if (beangleXml.isFile) GeneratorSupport.extractModuleClasses(beangleXml).foreach(classNames += _)
    classNames.toSeq
  }

  /** 从 beangle.xml 提取 web initializer 类（classes 清单，不要求是 AotHintRegistrar）。 */
  private[sbt] def collectClasses(beangleXml: File): Seq[String] = {
    if (beangleXml.isFile) GeneratorSupport.extractWebInitializerClasses(beangleXml) else Nil
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

  /** 删除输出目录中的残留配置文件（含迁移前的扁平目录残留）。 */
  private def deleteStaleConfigs(outDir: File): Unit = {
    ConfigNames.foreach { name =>
      (outDir / name).delete()
      (outDir.getParentFile / name).delete()
    }
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
        val files = ConfigNames.map(name => outDir / name).filter(_.exists())
        if (files.nonEmpty) log.info(s"Generated GraalVM configs in ${outDir.getAbsolutePath}")
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
