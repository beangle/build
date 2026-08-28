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

/** 生成器子进程辅助：失败分类、退出码驱动的重试与 beangle.xml 声明类提取。 */
private[sbt] object GeneratorSupport {

  /** 生成器子进程失败结果：exitCode 1 为确定性违约，2 为声明类未找到（编译/类物化未完成，可短时重试）。 */
  case class GenFailure(exitCode: Int, summary: String)

  object GenFailure {
    /** 归类子进程失败：退出码 2（声明类未找到）与输出含 ClassNotFoundException/NoClassDefFoundError
     * （生成器 main 类或其加载期依赖未就绪，如 sbt 2 依赖项目 classes 未物化）视为可重试（统一为 2），
     * 其余为确定性失败（1）。 */
    def of(exitCode: Int, output: String): GenFailure = {
      val summary = output.linesIterator.filter(_.nonEmpty).toSeq.lastOption.getOrElse(s"exited with code $exitCode")
      val retryable = exitCode == 2 ||
        output.contains("ClassNotFoundException") || output.contains("NoClassDefFoundError")
      GenFailure(if (retryable) 2 else 1, summary)
    }
  }

  /** 以生成器退出码驱动重试：0 成功、1 确定性失败立即报错、
   *  2（声明类未找到）静默退避重试直到 maxAttempts。
   *
   * 为何还需要重试：post 钩子虽在编译完成后运行，但 sbt 2 的 classDirectory
   * （CAS 符号链接/物化）可能在"done compiling"之后才真正可见，首次编译时
   * 生成器子进程可能短暂读不到刚编译的类。重试窗口很短，属时序兜底而非
   * "等编译完成"的替代。
   */
  def retryGenerator[T](maxAttempts: Int, attemptDelayMs: Long, task: String, log: Logger)(attempt: => Either[GenFailure, T]): T = {
    var i = 1
    while (i <= maxAttempts) {
      attempt match {
        case Right(value) => return value
        case Left(f) =>
          if (f.exitCode != 2) sys.error(s"$task failed: ${f.summary}")
          if (i == maxAttempts) sys.error(s"$task still failing after $maxAttempts attempts: ${f.summary}")
          log.debug(s"$task attempt $i failed (exit ${f.exitCode}): ${f.summary}; retrying in ${attemptDelayMs * i}ms")
          Thread.sleep(attemptDelayMs * i)
      }
      i += 1
    }
    sys.error(s"$task failed")
  }

  /** 从 beangle.xml 提取声明类：jpa/orm 的 mapping 与 cdi 的 module（带 class 属性）。 */
  def extractModuleClasses(beangleXml: File): Seq[String] = {
    extractClasses(beangleXml, Seq("mapping", "module"))
  }

  /** 从 beangle.xml 提取 jpa/orm 的 mapping 类（仅 mapping 元素，不含 cdi module；
   * 供 ProxyPlugin 生成懒加载代理使用）。 */
  def extractMappingClasses(beangleXml: File): Seq[String] = {
    extractClasses(beangleXml, Seq("mapping"))
  }

  private def extractClasses(beangleXml: File, tags: Seq[String]): Seq[String] = {
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
    tags foreach collect
    classNames.toSeq
  }
}
