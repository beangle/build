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

import java.io.File
import scala.collection.mutable.ArrayBuffer

/** 生成器子进程辅助：classes 目录快照，用于区分"编译仍在写入"与"classes 已稳定"。 */
private[sbt] object GeneratorSupport {

  /** classes 目录快照（.class 数量 + 最新修改时间）；目录不存在时返回 None。 */
  case class ClassesSnapshot(classCount: Long, lastModified: Long)

  /** 统计 classes 目录下 .class 文件数量与最新修改时间；目录不存在时返回 None。 */
  def snapshotClasses(dir: File): Option[ClassesSnapshot] = {
    if (!dir.isDirectory) return None
    var count = 0L
    var latest = 0L
    val stack = ArrayBuffer[File](dir)
    while (stack.nonEmpty) {
      val f = stack.remove(stack.size - 1)
      val children = f.listFiles()
      if (children != null) {
        var i = 0
        while (i < children.length) {
          val c = children(i)
          if (c.isDirectory) stack += c
          else if (c.getName.endsWith(".class")) {
            count += 1
            val m = c.lastModified()
            if (m > latest) latest = m
          }
          i += 1
        }
      }
    }
    Some(ClassesSnapshot(count, latest))
  }

  /** 从 beangle.xml 提取声明类：jpa/orm 的 mapping 与 cdi 的 module（带 class 属性）。 */
  def extractModuleClasses(beangleXml: File): Seq[String] = {
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(beangleXml)
    val classNames = scala.collection.mutable.LinkedHashSet.empty[String]
    def collect(tag: String): Unit = {
      val nodes = doc.getElementsByTagName(tag)
      var i = 0
      while (i < nodes.getLength) {
        val clazz = nodes.item(i).asInstanceOf[org.w3c.dom.Element].getAttribute("class").trim
        if (clazz.nonEmpty) classNames += clazz
        i += 1
      }
    }
    collect("mapping")
    collect("module")
    classNames.toSeq
  }
}
