/*
 * Beangle, Agile Development Scaffold and Toolkits.
 *
 * Copyright © 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
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

package org.beangle.build.util

import java.io.File

/** [[Bsdiff]] 的独立入口：大文件建后缀数组要几 GB 堆，放到子 JVM 里跑，别占 sbt 自己的堆。 */
object BsdiffMain {

  def main(args: Array[String]): Unit = {
    if (args.length != 3) {
      System.err.println("Usage: BsdiffMain <old> <new> <patch>")
      System.exit(2)
    }
    Bsdiff.diff(new File(args(0)), new File(args(1)), new File(args(2)))
  }
}
