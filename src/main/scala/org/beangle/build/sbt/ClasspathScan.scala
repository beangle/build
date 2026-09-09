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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.jar.JarFile
import scala.collection.mutable

/** 遍历 classpath 条目（classes/资源目录与 jar），按运行期 classpath 语义读取
 *  各依赖携带的声明锚点（aot/meta-registrars.txt、beangle.xml）。
 *
 *  中心化生成（终端项目启用 AotPlugin/MetaPlugin/ProxyPlugin）时，锚点与资源不再只
 *  取本模块，而是取自整个运行时 classpath：本模块 classes/资源目录 + 依赖项目
 *  classes/资源目录 + 外部依赖 jar。
 */
private[sbt] object ClasspathScan {

  private def isArchive(f: File): Boolean =
    f.isFile && (f.getName.endsWith(".jar") || f.getName.endsWith(".zip"))

  /** 读取所有条目中 `resourcePath` 对应的资源文本，按条目顺序返回；目录条目缺失、
   *  jar 打不开时静默跳过（与 classpath 扫描语义一致）。 */
  def readResources(entries: Seq[File], resourcePath: String): Seq[String] = {
    val normalized = resourcePath.replace('\\', '/')
    val out = Seq.newBuilder[String]
    distinct(entries).foreach { e =>
      if (e.isDirectory) {
        val f = new File(e, normalized)
        if (f.isFile) {
          try out += new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)
          catch case _: Exception => ()
        }
      } else if (isArchive(e)) {
        readJar(e, normalized).foreach(out += _)
      }
    }
    out.result()
  }

  /** 读取单个 jar 内指定条目的文本内容。 */
  private def readJar(jarFile: File, resourcePath: String): Option[String] = {
    try {
      val jar = new JarFile(jarFile)
      try {
        val entry = jar.getEntry(resourcePath)
        if (entry == null || entry.isDirectory) None
        else {
          val in = jar.getInputStream(entry)
          try Some(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          finally in.close()
        }
      } finally jar.close()
    } catch case _: Exception => None
  }

  /** 去重保序（按绝对路径）。 */
  def distinct(entries: Seq[File]): Seq[File] = {
    val seen = mutable.LinkedHashSet.empty[String]
    entries.filter(e => e != null && seen.add(e.getAbsolutePath))
  }
}
