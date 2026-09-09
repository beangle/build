# CompileHookPlugin 编译钩子扩展点

自动启用（`trigger = allRequirements`），属于基础设施插件。

## 用途

sbt 中多个 AutoPlugin 若各自覆写同一个 key（如 `Compile / compile`），后定义者会覆盖
前者，导致只有最后一个生效。本插件将"编译前后要执行的钩子"集中到一个唯一覆写点，
供 StylePlugin、AotPlugin、MetaPlugin、ProxyPlugin、BootPlugin 以及用户项目统一挂载。

## 提供的设置

| 设置 | 类型 | 说明 |
|------|------|------|
| `compilePreHooks` | `Seq[Task[Unit]]` | `Compile / compile` 之前执行的任务（如格式检查） |
| `compilePostHooks` | `Seq[Task[Unit]]` | `Compile / compile` 之后执行的任务（如各类生成器） |
| `testCompilePreHooks` | `Seq[Task[Unit]]` | `Test / compile` 之前执行的任务 |
| `testCompilePostHooks` | `Seq[Task[Unit]]` | `Test / compile` 之后执行的任务 |

## 行为

- 唯一覆写 `Compile / compile` 与 `Test / compile`：依次执行 pre 钩子 → 编译 → post 钩子；
- 在 `resourceGenerators` 中增加同步项，使 `packageBin` 打包时保证钩子已执行完毕，
  生成器产物（写入 `resourceManaged`）能随 jar 一并打包；
- 任务默认值为空列表，插件用 `+=` 追加自己的钩子。

## 在项目中自定义钩子

```scala
Compile / compilePostHooks += Def.task {
  // 编译后要做的事，例如生成代码
  ()
}.taskValue
```

## 注意事项

- `compile` 通过 `Def.uncached` 定义，钩子每次编译都会执行，不受 sbt 2 任务缓存影响；
- 不要在钩子里再求值 `Compile / compile`，避免任务依赖成环。

## 设计要点：生成器 classpath 与本模块资源

post 钩子（各类生成器）在 `compile` 完成后立即执行，而 `classDirectory` 中本模块的
资源（`src/main/resources` 下文件，如 `beangle.xml`）要等 `copyResources` 执行才会
物化，后者属于 `products`/`packageBin` 链，晚于钩子。因此在钩子运行时，`classDirectory`
只包含编译产物，不含本模块资源。

生成器子进程若只用 `classDirectory` + 依赖构建 classpath，加载按类路径扫描资源的
registrar 时会失败。实际案例：ems portal 模块 `clean` 后 `compile` 报
`cannot find beangle.xml,contains <ems> element.`——registrar（`DefaultModule` →
`CacheModule.binding` → `EmsApp.<clinit>`）扫描 `classpath*:beangle.xml` 找 `<ems>`，
而生成器 classpath 上只有 app 模块无 `<ems>` 的 `beangle.xml`。此前 `classDirectory`
残留旧资源掩盖了该问题，`clean` 后必现。

约定（AotPlugin/MetaPlugin/ProxyPlugin 统一实现，在终端项目启用）：生成器子进程
classpath 与锚点/资源扫描范围 = 本模块 `classDirectory` + 本模块
`unmanagedResourceDirectories` + 外部依赖 + 依赖项目 classes + 依赖项目资源目录
（汇总顺序见 `CpFiles.generatorEntries`）。终端集中式生成因此能遍历整个运行时
classpath，把各库声明的 registrars / `beangle.xml` 与实体类聚合后再交给生成器。
本模块类与资源放最前，与运行期"资源在 jar 中"的语义一致，避免依赖项目同名资源
遮蔽。等价于把 `src/main/resources` 提前到钩子可见，而不是调整钩子时机。

为什么不把钩子挪到 `copyResources` 之后或注册为 `resourceGenerators`：

- `resourceGenerators` 在资源拷贝**之前**执行，`classDirectory` 依旧没有本模块资源，
  时序问题不变；
- 覆写 `copyResources` 后置运行会让裸 `compile` 不再触发生成器，还得让 `compile`
  依赖它，形成 `compile → copyResources → resources → resourceGenerators(同步项) → compile`
  的循环；
- 生成器若读 `fullClasspath`/`dependencyClasspath`（含本模块 `products`，`exportJars`
  时为 `packageBin`），会形成 `resources → packageBin → resources` 环导致 sbt 卡死
  （历史踩坑，见 AotPlugin 注释）。
