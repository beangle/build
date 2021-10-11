/*
 * Copyright © 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.stat

import org.beangle.build.util.{Files, SourceFileWalker, Strings}
import org.beangle.build.util.SourceFileWalker

import java.io._
import scala.collection.mutable

object SlocStat {
  def countDir(path: File, excludes: Set[String]): Map[String, Int] = {
    val stats = new mutable.HashMap[String, Int]
    countDir(path, stats, excludes)
    stats.toMap
  }

  def countDir(path: File, stats: mutable.Map[String, Int], excludes: Set[String]): Unit = {
    if (path.exists()) {
      count(path, stats, excludes)
    }
  }

  private def count(dir: File, stats: collection.mutable.Map[String, Int], excludes: Set[String]): Unit = {
    SourceFileWalker.visit(dir, None, excludes, (dir: File) => {
      val reader = toBufferedReader(new InputStreamReader(new FileInputStream(dir)))
      var line = reader.readLine()
      var loc = 0
      while (line != null) {
        if (Strings.isNotBlank(line)) loc += 1
        line = reader.readLine()
      }
      Files.close(reader)
      val fileExt = Files.extension(dir)
      stats.get(fileExt) match {
        case Some(c) => stats.put(fileExt, c + loc)
        case None => stats.put(fileExt, loc)
      }
    })
  }

  private def toBufferedReader(reader: Reader): BufferedReader = {
    if (reader.isInstanceOf[BufferedReader]) reader.asInstanceOf[BufferedReader] else new BufferedReader(reader)
  }
}
