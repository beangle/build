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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import org.beangle.build.boot.Dependency
import org.beangle.build.util.Files./
import org.beangle.build.util.{Bsdiff, Files}
import sbt.Keys._
import sbt._

import java.io.File

object WarDiffPlugin extends sbt.AutoPlugin {

  object autoImport {
    val warDiff = inputKey[Unit]("Generate war diff")

    lazy val baseWarSettings: Seq[Def.Setting[_]] = Seq(
      warDiff := {
        val a = (Compile / Keys.`package` / artifact).value
        if (a.extension == "war") {
          import complete.DefaultParsers._
          val args = spaceDelimited("<arg>").parsed
          val log = streams.value.log
          if (args.size < 1) {
            log.error("usage:warDiff oldVersion [newVersion]")
          } else {
            val m = (Compile / projectID).value
            resolvers.value.find(_.isInstanceOf[MavenRepository]) foreach { mc =>
              val m2Root = mc.asInstanceOf[MavenRepository].root
              warDiffTask(m2Root, m, args.head, args.tail.headOption.getOrElse(m.revision),
                crossTarget.value.getAbsolutePath, scalaBinaryVersion.value, log)
            }
          }
        }
      }
    )
  }

  import autoImport._

  override def trigger = allRequirements

  override lazy val projectSettings = inConfig(Compile)(baseWarSettings)

  private def warDiffTask(m2Root: String, m: sbt.librarymanagement.ModuleID, oldVersion: String,
                          newVersion: String, crossTargetDir: String, sbv: String, log: util.Logger): Unit = {
    val aname = artifactName(m, sbv)
    val oldWar = new File(Dependency.m2Path(m2Root, m.organization, aname, oldVersion, ".war"))
    val newWar = new File(Dependency.m2Path(m2Root, m.organization, aname, newVersion, ".war"))

    if (!oldWar.exists) {
      log.error(s"Cannot find ${oldWar.getPath}")
    } else if (!newWar.exists) {
      log.error(s"Cannot find ${newWar.getPath}")
    } else {
      val diffFile = new File(Dependency.m2DiffPath(m2Root, m.organization, aname, oldVersion, newVersion, ".war"))
      log.info(s"Generating diff file ${diffFile.getPath}")
      Bsdiff.diff(oldWar, newWar, diffFile)
      Files.copy(diffFile, new File(crossTargetDir + / + diffFile.getName))
      log.info(s"Generated ${crossTargetDir}${/}${diffFile.getName}(${diffFile.length / 1000.0}KB)")
    }
  }

  private def artifactName(m: sbt.librarymanagement.ModuleID, sbv: String): String = {
    m.crossVersion match {
      case sbt.librarymanagement.Disabled => m.name
      case _: sbt.librarymanagement.Binary => m.name + "_" + sbv
      case _ => m.name
    }
  }
}
