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
