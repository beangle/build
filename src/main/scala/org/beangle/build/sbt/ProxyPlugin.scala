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

/** Generates Hibernate lazy-loading proxy classes from the MappingModule subclasses
 *  declared in `beangle.xml` (`<jpa>/<orm><mapping>` elements).
 *
 *  Auto-enabled on every JVM project. Generation is driven by two anchors:
 *  - `src/main/resources/beangle.xml` (or test-scope equivalent) with at least one mapping;
 *  - beangle-data-hibernate on the classpath (external jar, dependency project classes or
 *    the module itself).
 *  Projects without either are skipped without error, so no explicit opt-in is needed.
 *
 *  Forks `org.beangle.data.hibernate.aot.BeangleProxyGenerator` with the project classpath
 *  (plus the build plugin's own `net.bytebuddy:byte-buddy` jar, since applications may
 *  exclude ByteBuddy at runtime). Runs as a post-compile hook ([[CompileHookPlugin]]),
 *  so the classes are compiled before generation; exit-code-2 (declared class not
 *  found) is covered by a short retry for sbt 2's deferred classDirectory materialization.
 *  The generator writes into `Compile / resourceManaged` (packaged with the jar as
 *  resources, so the `.class` files are loadable at runtime):
 *  - the generated proxy `.class` files;
 *  - a GraalVM `META-INF/native-image/.../reflect-config.json` fragment.
 *
 *  Runtime consumption is done by the fork's `BeangleBytecodeProvider`, which loads the
 *  pre-generated classes by name (JVM and native share the same path).
 */
object ProxyPlugin extends sbt.AutoPlugin {

  private val BeangleXmlName = "beangle.xml"
  private val NativeConfigFile = "META-INF/native-image/beangle/data/reflect-config.json"
  private val GeneratorMain = "org.beangle.data.hibernate.aot.BeangleProxyGenerator"
  private val HibernateJarMarker = "beangle-data-hibernate"
  private val ByteBuddyClass = "net/bytebuddy/ByteBuddy.class"
  private val ByteBuddyVersion = "1.18.8"

  object autoImport {
    val proxyClasses = taskKey[Seq[File]]("Generate Hibernate lazy-loading proxy classes from beangle.xml jpa/orm mappings")
  }

  import autoImport.*

  override def trigger = allRequirements

  override val projectSettings: Seq[Setting[?]] = Seq(
    Compile / proxyClasses := Def.uncached {
      given FileConverter = fileConverter.value
      val log = streams.value.log
      val beangleXml = (Compile / resourceDirectory).value / BeangleXmlName
      if (!beangleXml.isFile) {
        log.debug(s"No $BeangleXmlName found; Hibernate proxy generation skipped")
        deleteStale((Compile / resourceManaged).value)
        Nil
      } else {
        val classesDir = (Compile / classDirectory).value
        val resDir = (Compile / resourceManaged).value
        val listFile = (Compile / target).value / "proxy" / "proxy-registrars.txt"
        val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
        val classpath = CpFiles.files((Runtime / externalDependencyClasspath).value) ++ depClasses :+ classesDir
        if (!hasHibernate(classpath)) {
          log.debug(s"No beangle-data-hibernate on classpath; Hibernate proxy generation skipped")
          deleteStale(resDir)
          Nil
        } else generate(beangleXml, listFile, resDir, classpath, log)
      }
    },
    Compile / compilePostHooks += Def.task {
      (Compile / proxyClasses).value
      ()
    }.taskValue,
    Test / proxyClasses := Def.uncached {
      // 编译时序由 Test / compilePostHooks 保证（compile 完成后执行），这里不能再依赖 compile，否则成环
      given FileConverter = fileConverter.value
      val log = streams.value.log
      val beangleXml = (Test / resourceDirectory).value / BeangleXmlName
      if (!beangleXml.isFile) {
        log.debug(s"No $BeangleXmlName (test) found; Hibernate proxy generation skipped")
        deleteStale((Test / resourceManaged).value)
        Nil
      } else {
        val classesDir = (Test / classDirectory).value
        val resDir = (Test / resourceManaged).value
        val listFile = (Test / target).value / "proxy" / "proxy-registrars.txt"
        val mainClasses = (Compile / classDirectory).value
        val depClasses = (Compile / classDirectory).all(ScopeFilter(inDependencies(ThisProject))).value
        val classpath = CpFiles.files((Test / externalDependencyClasspath).value) ++ depClasses :+ mainClasses :+ classesDir
        if (!hasHibernate(classpath)) {
          log.debug(s"No beangle-data-hibernate on test classpath; Hibernate proxy generation skipped")
          deleteStale(resDir)
          Nil
        } else generate(beangleXml, listFile, resDir, classpath, log)
      }
    },
    Test / compilePostHooks += Def.task {
      (Test / proxyClasses).value
      ()
    }.taskValue
  )

  /** 以 beangle.xml 的 jpa/orm mapping 类为契约生成代理：插件负责解析 beangle.xml
   * 决定读取哪些类（仅 mapping 元素），写入清单文件后交给 BeangleProxyGenerator。
   * 生成器对"声明类未找到"退出码 2 静默报告，这里退避重试；确定性违约（退出码 1）立即报错。
   */
  private def generate(beangleXml: File, listFile: File, resDir: File,
      classpath: Seq[File], log: Logger): Seq[File] = {
    val classNames = GeneratorSupport.extractMappingClasses(beangleXml)
    if (classNames.isEmpty) {
      log.debug(s"No mapping declared in $beangleXml; Hibernate proxy generation skipped")
      deleteStale(resDir)
      return Nil
    }
    writeList(listFile, classNames)
    // sbt 2 的虚拟路径条目（如 coursier ${CSR_CACHE} 引用）转换后可能不存在，过滤掉避免 java -cp 解析失败。
    // classpath 快照在每次重试内重算：依赖项目的 classDirectory 物化可能晚于编译完成，
    // 首次 fork 时尚未出现的目录在下次尝试即可用。
    GeneratorSupport.retryGenerator(10, 500L, "BeangleProxyGenerator", log) {
      runOnce(listFile, resDir, classpath.filter(_.exists()).map(_.getAbsolutePath), log)
    }
  }

  /** Runs the generator once; Right(files) 成功产出，Left(GenFailure) 按退出码分类失败。 */
  private def runOnce(listFile: File, resDir: File,
      cpEntries: Seq[String], log: Logger): Either[GeneratorSupport.GenFailure, Seq[File]] = {
    try {
      // 清掉上次残留产物，避免失败/跳过时把旧映射打进 jar
      deleteStale(resDir)
      val bytebuddy = bytebuddyJar(log)
      bytebuddy foreach (b => log.info(s"Using build plugin ByteBuddy: ${b.getAbsolutePath}"))
      val generatorCp = (bytebuddy.map(_.getAbsolutePath) ++ cpEntries).mkString(File.pathSeparator)
      val cmd = Seq("java", "-cp", generatorCp, GeneratorMain,
        "--registrars", listFile.getAbsolutePath,
        "-o", resDir.getAbsolutePath)
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
        val files = Seq(resDir / NativeConfigFile).filter(_.exists())
        if (files.nonEmpty) log.info(s"Generated Hibernate proxies in ${resDir.getAbsolutePath}")
        Right(files)
      } else {
        val output = out.toString
        log.debug(s"BeangleProxyGenerator exited with code $exitCode:\n$output")
        Left(GeneratorSupport.GenFailure.of(exitCode, output))
      }
    } catch {
      case e: Exception =>
        log.error(s"Failed to run BeangleProxyGenerator: ${e.getMessage}")
        Left(GeneratorSupport.GenFailure(1, s"failed to run: ${e.getMessage}"))
    }
  }

  /** 生成器 classpath 是否含 beangle-data-hibernate：外部 jar、依赖项目 classes 或本模块 classes。 */
  private def hasHibernate(classpath: Seq[File]): Boolean =
    classpath.exists(f => f.getAbsolutePath.contains(HibernateJarMarker))

  /** 定位构建插件自带的 net.bytebuddy jar，追加到生成器 classpath
   * （应用运行期可排除 ByteBuddy，不影响构建期生成）。
   * sbt 2 的 PluginClassLoader 不向插件代码暴露依赖类（getResource/Class.forName 均不可用），
   * 因此依次尝试：插件 classloader 资源 → 插件依赖类 codeSource → coursier/ivy 缓存路径。
   */
  private def bytebuddyJar(log: Logger): Option[File] = {
    val viaLoader = Option(getClass.getClassLoader.getResource(ByteBuddyClass)).flatMap(urlOf)
    val viaClass = try {
      val bb = Class.forName("net.bytebuddy.ByteBuddy")
      Option(bb.getProtectionDomain.getCodeSource).flatMap(s => Option(s.getLocation)).map(l => new File(l.toURI))
    } catch { case _: Throwable => None }
    val viaCache = coursierByteBuddy()
    val found = viaLoader.orElse(viaClass).orElse(viaCache)
    found.foreach(b => log.info(s"Using build plugin ByteBuddy: ${b.getAbsolutePath}"))
    found
  }

  private def urlOf(u: java.net.URL): Option[File] = {
    try {
      if (u.getProtocol == "jar") {
        val path = u.getPath
        val jarPath = path.substring(0, path.indexOf('!')).stripPrefix("file:")
        Some(new File(java.net.URLDecoder.decode(jarPath, "UTF-8")))
      } else if (u.getProtocol == "file") {
        // 插件以 classes 目录运行（开发/scripted）：向上定位到 classpath 根
        var d = new File(u.toURI).getParentFile
        while (d != null && d.getName != "net") d = d.getParentFile
        Option(d).map(_.getParentFile)
      } else None
    } catch { case _: Exception => None }
  }

  /** 从 coursier/ivy 缓存定位 byte-buddy jar：优先固定版本 1.18.8（与 hibernate 7.4 的
   *  ByteBuddyProxyHelper 编译版本一致），缺失时退避到最高版本。 */
  private def coursierByteBuddy(): Option[File] = {
    val home = System.getProperty("user.home")
    val candidates = Seq(
      new File(home, ".cache/coursier/v1/https/repo1.maven.org/maven2/net/bytebuddy/byte-buddy"),
      new File(home, ".ivy2/cache/net.bytebuddy/byte-buddy/jars"))
    val jars = candidates.flatMap { dir =>
      if (!dir.isDirectory) None
      else Option(dir.listFiles()).toSeq.flatten
        .filter(f => f.getName.startsWith("byte-buddy-") && f.getName.endsWith(".jar") &&
          !f.getName.contains("-sources") && !f.getName.contains("-javadoc"))
    }
    jars.find(_.getName == s"byte-buddy-$ByteBuddyVersion.jar")
      .orElse(jars.sortBy(_.getName).lastOption)
  }

  private def writeList(file: File, classNames: Seq[String]): Unit = {
    file.getParentFile.mkdirs()
    val w = new java.io.PrintWriter(file, java.nio.charset.StandardCharsets.UTF_8)
    try classNames.foreach(w.println)
    finally w.close()
  }

  /** 删除输出目录中的残留配置与上次生成的代理类（实体集合缩小时不残留旧代理）。 */
  private def deleteStale(resDir: File): Unit = {
    (resDir / NativeConfigFile).delete()
    (resDir / "META-INF/native-image/org/beangle/data/beangle-data-proxy/reflect-config.json").delete() // 迁移前旧布局
    deleteProxyClasses(resDir)
  }

  /** 递归删除按命名约定生成的 `<Entity>$HibernateProxy.class`（命名固定，无需清单文件）。 */
  private def deleteProxyClasses(dir: File): Unit = {
    val children = Option(dir.listFiles()).toSeq.flatten
    children foreach { f =>
      if (f.isDirectory) deleteProxyClasses(f)
      else if (f.getName.endsWith("$HibernateProxy.class")) f.delete()
    }
  }
}
