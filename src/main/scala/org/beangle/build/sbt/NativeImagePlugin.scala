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
