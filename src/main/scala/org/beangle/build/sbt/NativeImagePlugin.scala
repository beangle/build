package org.beangle.build.sbt

import sbt.*
import sbt.Keys.*
import sbt.plugins.JvmPlugin
import xsbti.FileConverter
import org.beangle.build.boot.Dependency
import org.beangle.build.util.{Bsdiff, IOs, Strings}

import java.io.{BufferedInputStream, BufferedOutputStream, File, FileInputStream, FileOutputStream}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.zip.GZIPInputStream
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
  override def requires = JvmPlugin && SnapshotPlugin
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
    val nativeDistDeltaBase: SettingKey[Option[String]] = settingKey[Option[String]](
      "Base version of nativeDistDelta, defaults to the greatest git tag lower than the current version.")
    val nativeDistDelta: TaskKey[File] = taskKey[File](
      "Generate a bsdiff patch between the previous native distribution and the current one.")
    val nativeDeltaRepoUrl: SettingKey[String] = settingKey[String](
      "Repository url for uploading the native distribution checksum and delta.")
    val nativeDeltaUpload: TaskKey[Unit] = taskKey[Unit](
      "Upload the native distribution sha1 and delta to the delta repository.")
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
    nativeDistDeltaBase := None,
    nativeDistDelta := Def.uncached {
      val log = streams.value.log
      val m2Root = nativeDistLocalRepo.value.getAbsolutePath
      val base = nativeDistDeltaBase.value.getOrElse(
        previousVersion(gitTags(baseDirectory.value), version.value).getOrElse(
          throw new MessageOnlyException(
            s"cannot find a git tag lower than ${version.value}, set nativeDistDeltaBase explicitly.")))
      val packaging = s"-${nativeDistClassifier.value}.tar.gz"
      val oldArtifact = new File(Dependency.m2Path(m2Root, organization.value, name.value, base, packaging))
      if (!oldArtifact.exists) then
        throw new MessageOnlyException(
          s"cannot find ${oldArtifact.getAbsolutePath}, run nativeDistInstall on $base or put that distribution into the local repository.")
      val current = nativeDist.value
      val patch = new File(Dependency.m2DiffPath(m2Root, organization.value, name.value, base, version.value, packaging))
      IO.createDirectory(patch.getParentFile)
      log.info(s"generating delta $base -> ${version.value}: ${patch.getName}")
      val heapMb = java.lang.Runtime.getRuntime.maxMemory / (1024 * 1024)
      if heapMb < 3000 then
        log.warn(s"bsdiff on ${oldArtifact.length / 1024 / 1024}MB files needs about 3GB heap, but max heap is ${heapMb}MB, restart sbt with -J-Xmx4G.")
      // 压缩流之间做 diff 出的补丁几乎等于整包，所以先解压成 tar 再比对。
      IO.withTemporaryDirectory { dir =>
        Bsdiff.diff(gunzip(oldArtifact, dir / "old.tar"), gunzip(current, dir / "new.tar"), patch)
      }
      writeSha1(patch)
      val out = current.getParentFile / patch.getName
      IO.copyFile(patch, out)
      log.info(s"generated ${out.absolutePath}(${out.length / 1000.0}KB)")
      out
    },
    nativeDeltaRepoUrl := "unknown-url",
    nativeDeltaUpload := Def.uncached {
      val log = streams.value.log
      val url = nativeDeltaRepoUrl.value
      val patch = nativeDistDelta.value
      val credentials = SnapshotPlugin.autoImport.snapshotCredentials.value
      if (url == "unknown-url") {
        log.error("set nativeDeltaRepoUrl := http://server/path/to/upload/{fileName} first.")
      } else {
        SnapshotPlugin.readCredentials(credentials) match {
          case Some((user, password)) =>
            val files = Seq(new File(nativeDist.value.getAbsolutePath + ".sha1"), patch, new File(patch.getAbsolutePath + ".sha1"))
            files.filter(_.exists).foreach { file =>
              val uploadUrl = Strings.replace(url, "{fileName}", file.getName)
              log.info(s"Uploading to $uploadUrl")
              val rs = SnapshotPlugin.upload(URI.create(uploadUrl).toURL, file, user, password)
              if (rs._1 == 200) log.info(s"Upload ${file.getName} success")
              else log.error(s"Upload ${file.getName} failed for status is ${rs._1} and reason is ${rs._2}")
            }
          case None =>
            log.error(s"Native delta upload is aborted: cannot find user or password in credentials file $credentials")
        }
      }
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

  /** 读取仓库中的版本 tag（按版本降序，git 自身排序）。 */
  private[sbt] def gitTags(dir: File): Seq[String] =
    try
      Process(Seq("git", "tag", "--sort=-v:refname"), cwd = Some(dir)).!!
        .linesIterator.map(_.trim).filter(_.nonEmpty).toSeq
    catch case _: Exception => Nil

  /** 取小于当前版本的最大 tag 版本；tag 列表需按版本降序。 */
  private[sbt] def previousVersion(tags: Seq[String], current: String): Option[String] = {
    val version = current.stripSuffix("-SNAPSHOT")
    tags.map(_.stripPrefix("v")).filter(_.headOption.exists(_.isDigit)).find(compareVersion(_, version) < 0)
  }

  /** 版本比较：按 `.`/`-` 切段，能转数字的按数字比，否则按字符串比。 */
  private[sbt] def compareVersion(left: String, right: String): Int = {
    val ls = left.split("[.-]")
    val rs = right.split("[.-]")
    var result = 0
    var i = 0
    while result == 0 && i < math.max(ls.length, rs.length) do
      val l = if i < ls.length then ls(i) else "0"
      val r = if i < rs.length then rs(i) else "0"
      result = (l.toIntOption, r.toIntOption) match {
        case (Some(x), Some(y)) => x.compare(y)
        case _                  => l.compareTo(r)
      }
      i += 1
    result
  }

  /** 解压 gzip 到目标文件。 */
  private[sbt] def gunzip(source: File, target: File): File = {
    val in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(source)))
    try
      val out = new BufferedOutputStream(new FileOutputStream(target))
      try IOs.copy(in, out)
      finally out.close()
    finally in.close()
    target
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
