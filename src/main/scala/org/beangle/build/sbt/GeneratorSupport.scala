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

import sbt.Logger

import java.io.File

/** 生成器子进程辅助：失败分类与退出码驱动的重试。 */
private[sbt] object GeneratorSupport {

  /** 生成器子进程失败结果：exitCode 1 为确定性违约，2 为声明类未找到（可重试）。 */
  case class GenFailure(exitCode: Int, summary: String)

  /** 以生成器退出码驱动重试：0 成功、1 确定性失败立即报错、
   *  2（声明类未找到，编译可能仍在进行）退避重试直到 maxAttempts。 */
  def retryGenerator[T](maxAttempts: Int, attemptDelayMs: Long, task: String, log: Logger)(attempt: => Either[GenFailure, T]): T = {
    var i = 1
    while (i <= maxAttempts) {
      attempt match {
        case Right(value) => return value
        case Left(f) =>
          if (f.exitCode != 2) sys.error(s"$task failed: ${f.summary}")
          if (i == maxAttempts) sys.error(s"$task still failing after $maxAttempts attempts: ${f.summary}")
          log.warn(s"$task attempt $i failed (exit ${f.exitCode}); retrying in ${attemptDelayMs * i}ms")
          Thread.sleep(attemptDelayMs * i)
      }
      i += 1
    }
    sys.error(s"$task failed")
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
