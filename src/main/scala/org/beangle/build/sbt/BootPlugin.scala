package org.beangle.build.sbt

import java.io.{File, FileWriter, IOException}
import sbt.Keys._
import sbt._

object BootPlugin extends sbt.AutoPlugin {

  private val fileName = "dependencies"
  private val inlcuded = Set("provided", "compile", "runtime")

  object autoImport {
    val bootDependencies = taskKey[Unit]("Generate boot dependencies file")
    lazy val baseBootSettings: Seq[Def.Setting[_]] = Seq(
      bootDependencies := generateDependenciesTask.value
    )
  }

  import autoImport._

  override lazy val projectSettings = inConfig(Compile)(baseBootSettings)

  override def trigger = allRequirements

  lazy val generateDependenciesTask =
    Def.task {
      val s = libraryDependencies.value
      generate(crossTarget.value.getAbsolutePath, s, streams.value.log)
    }

  def generate(target: String, dependencies: Seq[ModuleID], log: util.Logger): Unit = {
    val folder = target + "/classes/META-INF/beangle"
    new File(folder).mkdirs()
    val file = new File(folder + "/" + fileName)
    file.delete()
    try {
      file.createNewFile()
      val provideds = new collection.mutable.ArrayBuffer[String]
      dependencies foreach { m =>
        val scope = m.configurations.getOrElse("compile")
        if (inlcuded.contains(scope)) {
          provideds.append(s"${m.organization}:${m.name}:${m.revision}")
        }
      }
      val sb = new StringBuilder()
      if (provideds.contains("org.scala-lang:scala3-library_3:3.0.1") && !provideds.contains("org.scala-lang:scala-library:2.13.6")) {
        provideds += "org.scala-lang:scala-library:2.13.6"
      }
      provideds.sorted foreach { one =>
        sb.append(one).append('\n')
      }
      val fw = new FileWriter(file)
      fw.write(sb.toString)
      fw.close()
      log.info(s"Generated dependencies:(${provideds.size})" + file.getAbsolutePath)
    } catch {
      case e: IOException => e.printStackTrace()
    }
  }

}
