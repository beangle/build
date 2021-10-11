package org.beangle.build.sbt

import sbt.Keys._
import sbt._

import java.io.{File, FileWriter, IOException}

object BootPlugin extends sbt.AutoPlugin {

  private val fileName = "dependencies"

  object autoImport {
    val bootDependencies = taskKey[Unit]("Generate boot dependencies file")
    lazy val baseBootSettings: Seq[Def.Setting[_]] = Seq(
      bootDependencies := generateDependenciesTask.value
    )
  }

  import autoImport._

  override def requires: Plugins = {
    plugins.IvyPlugin
  }

  override lazy val projectSettings = inConfig(Compile)(baseBootSettings)

  override def trigger = allRequirements

  lazy val generateDependenciesTask =
    Def.task {
      val classpaths = new collection.mutable.ArrayBuffer[Attributed[File]]
      classpaths ++= (Compile / externalDependencyClasspath).value
      classpaths ++= (Runtime / externalDependencyClasspath).value
      classpaths ++= (Compile / internalDependencyClasspath).value
      generate(crossTarget.value.getAbsolutePath, classpaths, scalaBinaryVersion.value, streams.value.log)
    }

  def generate(target: String, dependencies: collection.Seq[Attributed[File]], scalaBinaryVersion: String, log: util.Logger): Unit = {
    val folder = target + "/classes/META-INF/beangle"
    new File(folder).mkdirs()
    val file = new File(folder + "/" + fileName)
    file.delete()
    try {
      file.createNewFile()
      val results = new collection.mutable.HashSet[String]
      dependencies foreach { d =>
        d.get(Keys.moduleID.key) match {
          case Some(m) =>
            val scope = m.configurations.getOrElse("compile")
            if ("test" != scope && !m.revision.contains("SNAPSHOT")) {
              val artifactName = {
                m.crossVersion match {
                  case sbt.librarymanagement.Disabled => m.name
                  case _: sbt.librarymanagement.Binary => m.name + "_" + scalaBinaryVersion
                  case _ => m.name
                }
              }
              val gav = s"${m.organization}:${artifactName}:${m.revision}"
              results += gav
            }
          case _ =>
        }
      }
      val fw = new FileWriter(file)
      fw.write(results.toSeq.sorted.mkString("\n"))
      fw.close()
      log.info(s"Generated dependencies:(${results.size}) at " + file.getAbsolutePath)
    } catch {
      case e: IOException => e.printStackTrace()
    }
  }

}
