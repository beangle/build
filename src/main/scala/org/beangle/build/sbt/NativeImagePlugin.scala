package org.beangle.build.sbt

import sbt.*
import sbt.Keys.*
import sbt.plugins.JvmPlugin
import xsbti.FileConverter
import org.beangle.build.util.{Bsdiff, IOs, Strings}
import org.beangle.build.util.BsdiffMain

import java.io.{BufferedInputStream, BufferedOutputStream, File, FileInputStream, FileOutputStream}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.zip.GZIPInputStream
import scala.sys.process.Process
import scala.util.Properties

/** [[NativeImagePlugin.nativeDist]] 的产物：发行包归档，以及能找到上一版本时生成的增量补丁。 */
final case class NativeDist(archive: File, delta: Option[File])

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
    val nativeDist: TaskKey[NativeDist] = taskKey[NativeDist](
      "Package the native image, install it into the local native repository and generate the delta from the previous version.")
    val nativeDistClassifier: SettingKey[String] = settingKey[String](
      "Platform classifier used in the nativeDist file name.")
    val nativeRepoHome: SettingKey[File] = settingKey[File](
      "Local maven home used as the native repository (default ~/.m2): releases install into <home>/repository, snapshots into <home>/snapshots.")
    val nativeRepoPath: SettingKey[String] = settingKey[String](
      "Repository relative path of this project, used as the {path} of nativeDeltaRepoUrl.")
    val nativeDistDeltaBase: SettingKey[Option[String]] = settingKey[Option[String]](
      "Base version of the delta, defaults to the greatest git tag lower than the current version.")
    val nativeDistDeltaHeap: SettingKey[String] = settingKey[String](
      "Heap of the forked bsdiff JVM, e.g. 4g. Unlike the sbt JVM it can be sized for the diff alone.")
    val nativePublishUrl: SettingKey[String] = settingKey[String](
      "Native repository url for publishing, e.g. https://host/sas/repo/native/upload/{path}/{fileName}.")
    val nativeDistUpload: TaskKey[Unit] = taskKey[Unit](
      "Upload the native distribution and its checksums to the native repository.")
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
    nativeRepoHome := sbt.Path.userHome / ".m2",
    nativeRepoPath := repositoryPath(organization.value, name.value, version.value),
    nativeDistDeltaBase := None,
    nativeDistDeltaHeap := "4g",
    nativeDist := Def.uncached {
      implicit val conv: FileConverter = fileConverter.value
      val log = streams.value.log
      val home = nativeRepoHome.value
      val root = repositoryRoot(home, version.value)
      // 增量基准只可能是正式版本(快照版不作为基准)，因此去 repository 根目录里找。
      val released = releaseRoot(home)
      val classifier = nativeDistClassifier.value
      // 触发链接并拿到可执行文件的真实路径（CAS 下是符号链接）
      val binary = conv.toPath(nativeImage.value).toFile
      val dir = binary.getParentFile
      val archive = dir.getParentFile / distFileName(name.value, version.value, classifier)
      packageDist(dir, distEntries(dir, binary), archive)
      writeChecksum(archive)
      writeSha1(archive)
      log.info(s"native distribution: ${archive.absolutePath}")
      log.info(s"native distribution sha1: ${digestHex(archive, "SHA-1")}")
      val installed = installDist(root, organization.value, name.value, version.value, archive)
      log.info(s"installed native distribution: ${installed.head.absolutePath}")

      // 增量补丁：本地仓库里没有更低的版本（或没有该版本的发行包）就跳过，构建照常成功。
      val base = nativeDistDeltaBase.value
        .orElse(previousLocalVersion(released, organization.value, name.value, version.value, classifier))
      val oldArtifact = base.map { b =>
        repositoryDir(repositoryRoot(home, b), organization.value, name.value, b) / distFileName(name.value, b, classifier)
      }
      val delta = oldArtifact match {
        case Some(old) if old.exists() =>
          // 快照包会反复构建，补丁名里带上构建时间；正式包只有一份，沿用 maven 风格的 diff 名。
          val buildNumber = if (version.value.contains("SNAPSHOT")) Some(SnapshotPlugin.timestampBuildNumber()) else None
          val patch = repositoryDir(root, organization.value, name.value, version.value) /
            deltaName(name.value, base.get, version.value, buildNumber, classifier)
          IO.createDirectory(patch.getParentFile)
          log.info(s"generating delta ${base.get} -> ${version.value}${buildNumber.map("-" + _).getOrElse("")}: ${patch.getName}")
          // 压缩流之间做 diff 出的补丁几乎等于整包，所以先解压成 tar 再比对；
          // bsdiff 要几 GB 堆，默认丢给子 JVM，免得要求调用者重启 sbt 加堆。
          IO.withTemporaryDirectory { tmp =>
            val oldTar = gunzip(old, tmp / "old.tar")
            val newTar = gunzip(archive, tmp / "new.tar")
            if !bsdiffInFork(oldTar, newTar, patch, nativeDistDeltaHeap.value) then
              val heapMb = java.lang.Runtime.getRuntime.maxMemory / (1024 * 1024)
              log.warn(s"forked bsdiff failed, falling back to the sbt JVM (max heap ${heapMb}MB).")
              Bsdiff.diff(oldTar, newTar, patch)
          }
          // 中断/失败的 diff 会留下空文件，宁可直接失败也不要让它在仓库里冒充补丁。
          if patch.length() == 0 then
            patch.delete()
            throw new MessageOnlyException(s"bsdiff produced no patch for ${patch.getName}")
          val patchSha1 = writeSha1(patch)
          val out = archive.getParentFile / patch.getName
          IO.copyFile(patch, out)
          IO.copyFile(patchSha1, new File(out.getAbsolutePath + ".sha1"))
          log.info(s"generated ${out.absolutePath}(${out.length / 1000.0}KB)")
          Some(out)
        case _ =>
          log.info(s"skip native delta: no local distribution older than ${version.value} under ${released.getAbsolutePath}")
          None
      }
      NativeDist(archive, delta)
    },
    nativePublishUrl := "unknown-url",
    nativeDistUpload := Def.uncached {
      val log = streams.value.log
      val path = nativeRepoPath.value
      val archive = new File(repositoryRoot(nativeRepoHome.value, version.value),
        s"$path/${distFileName(name.value, version.value, nativeDistClassifier.value)}")
      if (!archive.exists()) {
        log.warn(s"skip native distribution upload: cannot find ${archive.getAbsolutePath}, run nativeDist first.")
      } else {
        uploadToRepo(
          log,
          SnapshotPlugin.autoImport.snapshotCredentials.value,
          nativePublishUrl.value,
          path,
          distFiles(archive),
          "native distribution")
      }
    },
    nativeDeltaUpload := Def.uncached {
      val log = streams.value.log
      val path = nativeRepoPath.value
      val dir = new File(repositoryRoot(nativeRepoHome.value, version.value), path)
      findLatestDelta(dir, name.value, version.value, nativeDistClassifier.value) match {
        case None =>
          log.warn(s"skip native delta upload: cannot find any delta in ${dir.getAbsolutePath}, run nativeDist first.")
        case Some(delta) =>
          val archiveSha1 = new File(dir, distFileName(name.value, version.value, nativeDistClassifier.value) + ".sha1")
          val files = Seq(archiveSha1, delta, new File(delta.getAbsolutePath + ".sha1")).filter(_.exists)
          uploadToRepo(
            log,
            SnapshotPlugin.autoImport.snapshotCredentials.value,
            nativePublishUrl.value,
            path,
            files,
            "native delta")
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

  /** 正式版本的仓库根：`<home>/repository`。 */
  private[sbt] def releaseRoot(home: File): File = home / "repository"

  /** 开发版(快照)的仓库根：`<home>/snapshots`。 */
  private[sbt] def snapshotRoot(home: File): File = home / "snapshots"

  /** 版本对应的仓库根：正式版进 `<home>/repository`，`-SNAPSHOT` 进 `<home>/snapshots`。 */
  private[sbt] def repositoryRoot(home: File, version: String): File =
    if version.contains("SNAPSHOT") then snapshotRoot(home) else releaseRoot(home)

  /** 仓库内的相对路径：`<group 转路径>/<artifactId>/<version>`。 */
  private[sbt] def repositoryPath(groupId: String, artifactId: String, version: String): String =
    s"${groupId.replace('.', '/')}/$artifactId/$version"

  /** 用子 JVM 跑 bsdiff，堆由 `nativeDistDeltaHeap` 指定；启动失败返回 false。
    *
    * 子 JVM 的 classpath 由几个代表类的 code source 拼出：本插件的类、commons-compress（Bzip2 流），
    * 以及 Scala 运行库（本插件是 Scala 3 编译的，`Predef` 在 scala3-library、集合类在 scala-library）。
    */
  private[sbt] def bsdiffInFork(old: File, newFile: File, patch: File, heap: String): Boolean = {
    val classpath = Seq[Class[?]](BsdiffMain.getClass,
      classOf[org.apache.commons.compress.compressors.CompressorStreamFactory],
      scala.Predef.getClass,
      classOf[scala.collection.immutable.Seq[?]]).flatMap(codeSource)
      .map(_.getAbsolutePath).distinct
    if classpath.isEmpty then
      false
    else
      val java = s"${System.getProperty("java.home")}/bin/java"
      val command = Seq(java, s"-Xmx$heap", "-cp", classpath.mkString(File.pathSeparator),
        "org.beangle.build.util.BsdiffMain", old.getAbsolutePath, newFile.getAbsolutePath, patch.getAbsolutePath)
      Process(command).! == 0 && patch.exists() && patch.length() > 0
  }

  /** 类所在的 jar 或 classes 目录，用来拼子 JVM 的 classpath。 */
  private def codeSource(clazz: Class[?]): Option[File] = {
    Option(clazz.getProtectionDomain).flatMap(pd => Option(pd.getCodeSource)).flatMap(cs => Option(cs.getLocation))
      .map(url => try new File(url.toURI) catch case _: Exception => new File(url.getPath))
  }

  /** 发行包及其校验文件：归档、maven 风格的 `.sha1`、`sha256sum` 风格的 `.sha256`。 */
  private[sbt] def distFiles(dist: File): Seq[File] =
    Seq(dist, new File(dist.getAbsolutePath + ".sha1"), new File(dist.getAbsolutePath + ".sha256"))
      .filter(_.exists)

  /** 发行包文件名：`<name>-<version>-<classifier>.tar.gz`。 */
  private[sbt] def distFileName(artifactId: String, version: String, classifier: String): String =
    s"$artifactId-$version-$classifier.tar.gz"

  /** 版本目录里最新的增量补丁：`<name>-<基准版本>_<版本>[-<UTC 构建号>]-<classifier>.tar.gz.diff`。 */
  private[sbt] def findLatestDelta(dir: File, artifactId: String, version: String, classifier: String): Option[File] = {
    val marker = s"_$version"
    val suffix = s"-$classifier.tar.gz.diff"
    val children = Option(dir.list()).getOrElse(Array.empty[String])
    val candidates = children.filter { c =>
      c.startsWith(s"$artifactId-") && c.contains(marker) && c.endsWith(suffix) && new File(dir, c).length() > 0
    }
    if (candidates.isEmpty) None
    else Some(new File(dir, candidates.maxBy(c => deltaBuildNumber(c, marker, suffix))))
  }

  /** 从补丁名里取构建号：`..._<版本>-20260913.101500-1-linux-amd64.tar.gz.diff` 取 `-20260913.101500-1`，正式包为空。 */
  private[sbt] def deltaBuildNumber(fileName: String, versionMarker: String, suffix: String): String = {
    val start = fileName.indexOf(versionMarker) + versionMarker.length
    val end = fileName.length - suffix.length
    if (start >= versionMarker.length && end > start) fileName.substring(start, end) else ""
  }

  /** 用仓库内相对路径和文件名填充地址模板里的 `{path}` / `{fileName}`。 */
  private[sbt] def fill(url: String, path: String, fileName: String): String =
    Strings.replace(Strings.replace(url, "{path}", path), "{fileName}", fileName)

  /** 从发布地址推下载地址：去掉 `/upload` 一段（只为日志方便，未包含时返回原样）。 */
  private[sbt] def downloadUrl(publishUrl: String, path: String, fileName: String): String =
    fill(publishUrl.replace("/upload", ""), path, fileName)

  /** 上传文件到 native 仓库，`publishUrl` 是含 `{path}`/`{fileName}` 占位符的发布地址。 */
  private def uploadToRepo(
      log: sbt.util.Logger,
      credentials: File,
      publishUrl: String,
      path: String,
      files: Seq[File],
      label: String): Unit = {
    if (publishUrl == "unknown-url") {
      log.error("set nativePublishUrl := https://host/sas/repo/native/upload/{path}/{fileName} first.")
    } else if (files.isEmpty) {
      log.error(s"no $label to upload")
    } else {
      SnapshotPlugin.readCredentials(credentials) match {
        case Some((user, password)) =>
          files.foreach { file =>
            val url = fill(publishUrl, path, file.getName)
            log.info(s"Uploading to $url")
            val rs = SnapshotPlugin.upload(URI.create(url).toURL, file, user, password)
            if (rs._1 == 200) {
              log.info(s"Upload ${file.getName} success, download it from ${downloadUrl(publishUrl, path, file.getName)}")
            } else {
              log.error(s"Upload ${file.getName} failed for status is ${rs._1} and reason is ${rs._2}")
            }
          }
        case None =>
          log.error(s"Native upload is aborted: cannot find user or password in credentials file $credentials")
      }
    }
  }

  /** 仓库内的版本目录：`<root>/<group 转路径>/<artifactId>/<version>`。 */
  private[sbt] def repositoryDir(root: File, groupId: String, artifactId: String, version: String): File =
    root / repositoryPath(groupId, artifactId, version)

  /** 增量补丁文件名：`<name>-<基准版本>_<当前版本>[-<UTC 时间戳>-<构建号>]-<classifier>.tar.gz.diff`。
    *
    * 快照包同名版本会反复构建，用构建号区分；正式包不传构建号，与 `WarPlugin.warDiff` 命名一致。
    */
  private[sbt] def deltaName(
      artifactId: String,
      baseVersion: String,
      version: String,
      buildNumber: Option[String],
      classifier: String): String = {
    val stamp = buildNumber.map(n => s"-$n").getOrElse("")
    s"$artifactId-${baseVersion}_$version$stamp-$classifier.tar.gz.diff"
  }

  /** 按仓库布局安装发行包：复制归档并生成 maven 风格的 `.sha1`（只装 tar.gz 一类发行包，不产生 pom）。 */
  private[sbt] def installDist(
      root: File,
      groupId: String,
      artifactId: String,
      version: String,
      artifact: File): Seq[File] = {
    val dir = repositoryDir(root, groupId, artifactId, version)
    IO.createDirectory(dir)
    val installed = dir / artifact.getName
    IO.copyFile(artifact, installed)
    Seq(installed, writeSha1(installed), writeChecksum(installed))
  }

  /** 本地仓库中可作增量基准的版本：正式版本，且该版本目录里确实存有本平台的发行包。
    *
    * 快照版本不参与：它多半是本机反复构建的试验产物，未必发布过，拿它当基准对客户端没有意义。
    */
  private[sbt] def localVersions(
      root: File,
      groupId: String,
      artifactId: String,
      classifier: String): Seq[String] = {
    val parent = root / groupId.replace('.', '/') / artifactId
    Option(parent.list())
      .getOrElse(Array.empty[String])
      .filter(v => isVersionLike(v) && !v.contains("SNAPSHOT"))
      .filter { v => (new File(parent, s"$v/${distFileName(artifactId, v, classifier)}")).exists() }
      .toSeq
  }

  /** 取本地仓库中小于当前版本的最大正式版本；一个都没有则返回 None，即快照版本之间不互相作基准。
    *
    * 比较用的是 [[compareVersion]]：`4.20.13` 大于 `4.20.9`，因为数字按数字比而不是按目录名字典序。
    */
  private[sbt] def previousLocalVersion(
      root: File,
      groupId: String,
      artifactId: String,
      current: String,
      classifier: String): Option[String] =
    localVersions(root, groupId, artifactId, classifier)
      .filter(compareVersion(_, current) < 0)
      .sortWith((left, right) => compareVersion(left, right) > 0)
      .headOption

  /** 目录名是否像版本号：以数字开头，其余只允许数字/字母/点/下划线/连字符。 */
  private[sbt] def isVersionLike(dirName: String): Boolean =
    dirName.headOption.exists(_.isDigit) && dirName.matches("[0-9][0-9A-Za-z._-]*")

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
