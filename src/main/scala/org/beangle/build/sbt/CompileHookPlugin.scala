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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.beangle.build.sbt

import sbt.*
import sbt.Keys.*

/** 共享编译钩子：所有需要在 `compile` 前后执行的插件任务统一挂到 pre/post 两组，
 * 由本插件唯一覆写 `Compile / compile` 与 `Test / compile` 来执行。
 *
 * 为什么需要共享扩展点：sbt 中多个 AutoPlugin 若各自覆写同一个 key，后定义者会覆盖前者，
 * 只有最后一个生效。集中到这里后，style（pre）与 aot/meta/proxy/boot 生成器（post）
 * 只是往 hooks 里追加任务，互不冲突。
 *
 * - pre：编译前运行（StylePlugin 的 styleCheck）；
 * - post：编译后运行（各生成器：读已编译类 → 写 `resourceManaged`，随 jar 作为资源打包）。
 *   post-compile 保证本模块及依赖项目类已就绪；sbt 2 的 classDirectory 物化可能晚于
 *   "done compiling"，生成器对"声明类未找到"（退出码 2）保留短时重试兜底（见 GeneratorSupport）。
 *   生成器不注册在 `resourceGenerators`——sbt 默认 `resourceDirectories` 含 `resourceManaged`，
 *   `packageBin` 会直接打包其中的文件。
 *
 * sbt 2 的打包竞态：`products`/`packageBin` 走 `compileIncremental`（绕过本插件的
 * `compile` 覆写），其资源快照可能与 post 钩子的写入并行执行，导致 jar 缺产物。
 * 因此这里在 `resourceGenerators` 中各加一个同步项：让 `resources` 依赖覆写后的
 * `compile`（钩子在其中执行完毕），`products`（makeProducts 会求值 resources）与
 * `packageBin` 的快照必然发生在钩子之后。生成器本身不注册在 `resourceGenerators`，
 * 仍由 post 钩子驱动，因此不会引入 resourceGenerators → classpath → products 环。
 */
object CompileHookPlugin extends sbt.AutoPlugin {

  object autoImport {
    // 与 resourceGenerators 同款：SettingKey[Seq[Task[Unit]]]，插件用 += 追加任务。
    // 不可缓存（含 Task 值），需 @transient 退出 sbt 2 任务缓存（Keys.scala 同款用法）。
    @transient
    val compilePreHooks = settingKey[Seq[Task[Unit]]]("Tasks to run before Compile / compile (e.g. style check)")
    @transient
    val compilePostHooks = settingKey[Seq[Task[Unit]]]("Tasks to run after Compile / compile (e.g. aot/meta/proxy/boot generators)")
    @transient
    val testCompilePreHooks = settingKey[Seq[Task[Unit]]]("Tasks to run before Test / compile")
    @transient
    val testCompilePostHooks = settingKey[Seq[Task[Unit]]]("Tasks to run after Test / compile")
  }

  import autoImport.*

  override def trigger = allRequirements

  // 默认值放 globalSettings：全局默认先于任何项目的 += 生效，避免"某插件的 += 在
  // 本项目 := Nil 之前求值、随后被清空"的插件顺序问题。
  override val globalSettings: Seq[Setting[?]] = Seq(
    compilePreHooks := Nil,
    compilePostHooks := Nil,
    testCompilePreHooks := Nil,
    testCompilePostHooks := Nil
  )

  override val projectSettings: Seq[Setting[?]] = Seq(
    // Def.uncached：compile 不被缓存（钩子每次编译都执行；beangle.xml 等钩子输入不在
    // compile 缓存键里）。compile 自引用保持 StylePlugin 的直接形态（`:=` 宏会做 Previous 委托，
    // 嵌进嵌套 Def.task/taskDyn 会解析为 undefined settings）。
    // hooks 用 key.apply(f)（generate(resourceGenerators) 同款：参数是 key，非 .value 结果）
    // 在值层面 join 成单个任务后执行。
    Compile / compile := Def.uncached {
      runHooks(Compile / compilePreHooks).value
      val result = (Compile / compile).value
      runHooks(Compile / compilePostHooks).value
      result
    },
    Test / compile := Def.uncached {
      runHooks(Test / compilePreHooks).value
      val result = (Test / compile).value
      runHooks(Test / compilePostHooks).value
      result
    },
    // 同步项：resources → compile（覆写后的 compile 含全部 pre/post 钩子）。
    // compile 的 classpath 是 dependencyClasspath（不含本项目 products/resources），
    // 故 resources → compile 无环；钩子内生成器只读 externalDependencyClasspath 与
    // classDirectory，同样不与 resources/products 成环。
    Compile / resourceGenerators += Def.task {
      (Compile / compile).value
      Seq.empty[File]
    }.taskValue,
    Test / resourceGenerators += Def.task {
      (Test / compile).value
      Seq.empty[File]
    }.taskValue
  )

  /** 把 hook 列表 join 成单个任务（Defaults.generate 同款：key.apply(f)，f 返回 Task）。 */
  private def runHooks(hooksKey: SettingKey[Seq[Task[Unit]]]): Def.Initialize[Task[Unit]] =
    hooksKey { _.join.map(_ => ()) }
}
