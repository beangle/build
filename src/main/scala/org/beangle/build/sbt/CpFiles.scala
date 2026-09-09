/*
 * Copyright (C) 2005, The Beangle Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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

import sbt.*
import sbt.Keys.*
import xsbti.{FileConverter, HashedVirtualFileRef}

import java.io.File as JFile

/** sbt 2 classpath helpers（`HashedVirtualFileRef` → `java.io.File`）。 */
object CpFiles {
  def file(entry: Attributed[HashedVirtualFileRef])(using conv: FileConverter): JFile =
    conv.toPath(entry.data).toFile

  def files(cp: Seq[Attributed[HashedVirtualFileRef]])(using FileConverter): Seq[JFile] =
    cp.map(file)

  /** 汇总生成器可见的 classpath 条目（目录或 jar），按运行期优先级去重：
   *  本模块 classes → 本模块资源目录 → 外部依赖 → 依赖项目 classes → 依赖项目资源目录。
   *
   *  中心化生成（AotPlugin/MetaPlugin/ProxyPlugin 在终端项目启用）以此为锚点与资源
   *  扫描范围：内容来自本模块与全部依赖库。
   */
  def generatorEntries(ownClasses: JFile, ownResourceDirs: Seq[JFile], external: Seq[JFile],
      depClasses: Seq[JFile], depResourceDirs: Seq[Seq[JFile]]): Seq[JFile] = {
    val all = ownClasses +: (ownResourceDirs ++ external ++ depClasses ++ depResourceDirs.flatten)
    ClasspathScan.distinct(all)
  }
}
