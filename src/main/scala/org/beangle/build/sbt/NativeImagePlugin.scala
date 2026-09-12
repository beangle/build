package org.beangle.build.sbt

import sbt.*
import sbt.Keys.*
import sbt.plugins.JvmPlugin
import xsbti.FileConverter
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.sys.process.Process
import scala.util.Properties

/** Minimal GraalVM native-image launcher for sbt 2.
  *
  * 只保留 EMS 实际使用的核心能力：定位已安装的 `native-image`、把全部参数写进
  * `@argument file`（避免 `-cp` 参数过长、命令可读）、执行构建。无 coursier 下载、
  * 无 Test/agent 变体、无 manifest.jar（其 Class-Path 会被驱动二次展开）。
  *
  * Classpath 条目在写入参数文件前统一 realpath：sbt 2 的 CAS 使产物 jar 的 id 是
  * `${OUT}/…` 符号链接，驱动展开时也会 realpath 成 `~/.cache/sbt/v2/cas/…` 物理
  * 路径，同一文件会出现两条 URL（模块/资源双份加载）。提前归一化后收敛为单一路径。
  *
  * 归一化后立即校验无重复条目，写盘后按参数文件再校验一次：同一个 jar 被收录两次
  * 会让 native-image 以两条 URL 注册其类与资源（`getResources` 返回双份，模块/
  * 初始化器/静态资源重复注册），这里直接 fail，而不是留到运行期去容错。
  */
object NativeImagePlugin extends sbt.AutoPlugin {
  override def requires = JvmPlugin
  override def trigger = noTrigger

  object autoImport {
    val NativeImage: Configuration = config("native-image")
    @transient val nativeImage: TaskKey[xsbti.VirtualFileRef] = taskKey[xsbti.VirtualFileRef](
      "Link a GraalVM native image for this project.")
    val nativeImageCommand: TaskKey[Seq[String]] = taskKey[Seq[String]](
      "Command to launch the native-image binary.")
    val nativeImageOptions: TaskKey[Seq[String]] = taskKey[Seq[String]](
      "Extra arguments passed to the native-image optimizer.")
    @transient val nativeImageOutput: TaskKey[xsbti.VirtualFileRef] = taskKey[xsbti.VirtualFileRef](
      "The binary produced by native-image.")
    val nativeDist: TaskKey[File] = taskKey[File](
      "Package the native image (binary + runtime libraries) into a distributable archive.")
    val nativeDistClassifier: SettingKey[String] = settingKey[String](
      "Platform classifier used in the nativeDist file name.")
    val nativeDistLocalRepo: SettingKey[File] = settingKey[File](
      "Local maven repository that nativeDistInstall writes to (default ~/.m2/repository).")
    val nativeDistInstall: TaskKey[Seq[File]] = taskKey[Seq[File]](
      "Install the native distribution and its pom into the local maven repository.")
  }

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    NativeImage / target := (Compile / target).value / "native-image",
    nativeImageCommand := {
      val bin = if Properties.isWin then "native-image.cmd" else "native-image"
      val home = Seq("GRAAL_HOME", "GRAALVM_HOME", "JAVA_HOME")
        .flatMap(k => sys.env.get(k))
        .map(Paths.get(_).resolve("bin").resolve(bin))
        .find(Files.isExecutable(_))
      home.map(_.toString).getOrElse(bin) :: Nil
    },
    nativeImageOptions := Nil,
    nativeImageOutput := {
      val out = (NativeImage / target).value / name.value
      xsbti.VirtualFileRef.of(out.absolutePath)
    },
    nativeDistClassifier := platformClassifier(System.getProperty("os.name"), System.getProperty("os.arch")),
    nativeDistLocalRepo := sbt.Path.userHome / ".m2" / "repository",
    nativeDist := Def.uncached {
      implicit val conv: FileConverter = fileConverter.value
      // 触发链接并拿到可执行文件的真实路径（CAS 下是符号链接）
      val binary = conv.toPath(nativeImage.value).toFile
      val dir = binary.getParentFile
      val out = dir.getParentFile / s"${name.value}-${version.value}-${nativeDistClassifier.value}.tar.gz"
      packageDist(dir, distEntries(dir, binary), out)
      writeChecksum(out)
      writeSha1(out)
      streams.value.log.info(s"native distribution: ${out.absolutePath}")
      streams.value.log.info(s"native distribution sha1: ${digestHex(out, "SHA-1")}")
      out
    },
    nativeDistInstall := Def.uncached {
      implicit val conv: FileConverter = fileConverter.value
      val artifact = nativeDist.value
      val installed = installToMavenLocal(
        nativeDistLocalRepo.value,
        organization.value,
        name.value,
        version.value,
        artifact,
        conv.toPath(makePom.value).toFile)
      streams.value.log.info(s"installed native distribution: ${installed.head.absolutePath}")
      installed
    },
    nativeImage := Def.uncached {
      val _ = (Compile / products).value
      val main = (Compile / mainClass).value.getOrElse(
        throw new MessageOnlyException(
          "no mainClass is specified. Add `Compile / mainClass := Some(\"...\")`."))
      implicit val conv: FileConverter = fileConverter.value
      val output = conv.toPath(nativeImageOutput.value).toFile
      output.getParentFile.mkdirs()

      // 全部参数写入 @argument file：classpath 条目先 realpath 归一化（CAS 链接 → 物理路径）。
      val cp = (Compile / fullClasspath).value.map(a => realPath(conv.toPath(a.data)))
      assertNoDuplicates(cp.map(_.toString), "classpath")
      val cpStr = cp.mkString(File.pathSeparator)
      val dir = (NativeImage / target).value
      dir.mkdirs()
      val argsFile = dir / "native-image.args"
      writeArgsFile(
        argsFile,
        Seq("-cp", cpStr) ++ nativeImageOptions.value ++ Seq(main, output.absolutePath))
      assertNoDuplicates(readArgsClasspath(argsFile), s"${argsFile.getName} -cp")

      val command = nativeImageCommand.value :+ s"@${argsFile.absolutePath}"
      streams.value.log.info(command.mkString(" "))
      val exit = Process(command, cwd = Some(dir)).!
      if exit != 0 then throw new MessageOnlyException(s"native-image failed with exit code '$exit'")
      nativeImageOutput.value
    }
  )

  /** 发行包文件名里的平台标签：`linux-amd64` / `darwin-arm64` / `windows-amd64`。 */
  private[sbt] def platformClassifier(osName: String, osArch: String): String = {
    val os =
      if osName.startsWith("Linux") then "linux"
      else if osName.contains("Mac") || osName.contains("Darwin") then "darwin"
      else if osName.startsWith("Windows") then "windows"
      else osName.toLowerCase.replaceAll("[^a-z0-9]+", "-")
    val arch = osArch match {
      case "amd64" | "x86_64"  => "amd64"
      case "aarch64" | "arm64" => "arm64"
      case other               => other
    }
    s"$os-$arch"
  }

  /** 发行目录内容：可执行文件 + 运行时动态库（native-image 生成的 `lib*.so`），构建中间产物（`*.args` 等）不入包。 */
  private[sbt] def distEntries(dir: File, binary: File): Seq[File] = {
    val libs = Option(dir.listFiles()).getOrElse(Array.empty[File]).filter(_.getName.endsWith(".so")).sortBy(_.getName)
    binary +: libs.toSeq
  }

  /** 打包为 tar.gz：gzip 在任何目标机上都能用系统 tar 直接解开（CentOS 8 的 tar 1.30 不支持 --zstd）。 */
  private[sbt] def packageDist(dir: File, entries: Seq[File], out: File): File = {
    val names = entries.map(e => e.relativeTo(dir).map(_.getPath).getOrElse(e.getName))
    val command = Seq("tar", "-z", "-cf", out.absolutePath, "-C", dir.absolutePath) ++ names
    out.delete()
    val exit = Process(command).!
    if exit != 0 then throw new MessageOnlyException(s"tar failed with exit code '$exit': ${command.mkString(" ")}")
    out
  }

  /** 计算摘要的十六进制表示。 */
  private[sbt] def digestHex(file: File, algorithm: String): String = {
    val digest = java.security.MessageDigest.getInstance(algorithm)
    val in = new java.io.FileInputStream(file)
    try {
      val buf = new Array[Byte](64 * 1024)
      var n = in.read(buf)
      while n >= 0 do
        digest.update(buf, 0, n)
        n = in.read(buf)
    } finally in.close()
    digest.digest().map(b => f"$b%02x").mkString
  }

  /** 生成 sha256sum 兼容的校验文件（`<hash>  <file>`）。 */
  private[sbt] def writeChecksum(file: File): File = {
    val out = new File(file.getAbsolutePath + ".sha256")
    IO.write(out, s"${digestHex(file, "SHA-256")}  ${file.getName}\n")
    out
  }

  /** 生成 maven 风格的 `.sha1`（内容只有 40 位十六进制摘要）。 */
  private[sbt] def writeSha1(file: File): File = {
    val out = new File(file.getAbsolutePath + ".sha1")
    IO.write(out, digestHex(file, "SHA-1"))
    out
  }

  /** 按 maven 本地仓库布局安装工件：`<repo>/<group>/<artifactId>/<version>/`，附带 pom 和 `.sha1`。 */
  private[sbt] def installToMavenLocal(
      repo: File,
      groupId: String,
      artifactId: String,
      version: String,
      artifact: File,
      pom: File): Seq[File] = {
    val dir = repo / groupId.replace('.', '/') / artifactId / version
    IO.createDirectory(dir)
    val installed = dir / artifact.getName
    IO.copyFile(artifact, installed)
    val installedPom = dir / s"$artifactId-$version.pom"
    IO.copyFile(pom, installedPom)
    Seq(installed, writeSha1(installed), installedPom, writeSha1(installedPom))
  }

  /** 解析符号链接得到物理路径；路径不可解析时保持原样。 */
  private def realPath(p: Path): Path =
    try p.toRealPath()
    catch case _: Exception => p

  /** 拒绝重复条目：重复即构建失败，并列出重复项。 */
  private[sbt] def assertNoDuplicates(entries: Seq[String], label: String): Unit = {
    val duplicated = entries.groupBy(identity).collect { case (e, xs) if xs.sizeIs > 1 => e }.toSeq.sorted
    if duplicated.nonEmpty then
      throw new MessageOnlyException(s"duplicated $label entries: ${duplicated.mkString(", ")}")
  }

  /** 读回参数文件里最终的 `-cp`，用于写盘后的二次校验。 */
  private[sbt] def readArgsClasspath(file: File): Seq[String] = {
    val lines = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8).linesIterator.toSeq
    val index = lines.indexOf("-cp")
    if index < 0 || index + 1 >= lines.size then Nil
    else unquote(lines(index + 1)).split(File.pathSeparator).toSeq
  }

  private def writeArgsFile(file: File, args: Seq[String]): Unit =
    IO.write(file, args.map(quoteIfNeeded).mkString("\n") + "\n")

  /** 参数文件按空白拆分 token，含空白/引号的参数需加引号包裹。 */
  private[sbt] def quoteIfNeeded(arg: String): String =
    if arg.exists(c => c.isWhitespace || c == '"' || c == '\\') then
      "\"" + arg.flatMap {
        case '"'  => "\\\""
        case '\\' => "\\\\"
        case c    => c.toString
      } + "\""
    else arg

  /** [[quoteIfNeeded]] 的逆操作。 */
  private[sbt] def unquote(arg: String): String =
    if arg.length >= 2 && arg.head == '"' && arg.last == '"' then
      val buf = new StringBuilder
      var i = 1
      while i < arg.length - 1 do
        if arg.charAt(i) == '\\' && i + 1 < arg.length - 1 then
          buf.append(arg.charAt(i + 1))
          i += 2
        else
          buf.append(arg.charAt(i))
          i += 1
      buf.toString
    else arg
}
