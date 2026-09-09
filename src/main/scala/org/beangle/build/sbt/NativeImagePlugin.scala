package org.beangle.build.sbt

import sbt.*
import sbt.Keys.*
import sbt.plugins.JvmPlugin
import xsbti.FileConverter

import java.io.File
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
      val cpStr = cp.mkString(File.pathSeparator)
      val dir = (NativeImage / target).value
      dir.mkdirs()
      val argsFile = dir / "native-image.args"
      writeArgsFile(
        argsFile,
        Seq("-cp", cpStr) ++ nativeImageOptions.value ++ Seq(main, output.absolutePath))

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

  private def writeArgsFile(file: File, args: Seq[String]): Unit =
    IO.write(file, args.map(quoteIfNeeded).mkString("\n") + "\n")

  /** 参数文件按空白拆分 token，含空白/引号的参数需加引号包裹。 */
  private def quoteIfNeeded(arg: String): String =
    if arg.exists(c => c.isWhitespace || c == '"' || c == '\\') then
      "\"" + arg.flatMap {
        case '"'  => "\\\""
        case '\\' => "\\\\"
        case c    => c.toString
      } + "\""
    else arg
}
