# BootPlugin 运行时依赖元数据

自动启用（`trigger = allRequirements`）。

## 用途

为 [beangle boot](https://github.com/beangle/boot) 一键启动生成运行时依赖清单与本地仓库：

- `bootDependencies`：生成 `META-INF/beangle/dependencies`，每行一个 Maven 坐标
  `groupId:artifactId:version`（GAV），随 jar 打包；
- `bootRepo`：把解析到的 jar 复制为 Maven 目录布局到 `target/repository`。

包含该依赖描述文件的 jar/war 可用 beangle boot 直接拉起应用。

## 任务

| 任务 | 说明 |
|------|------|
| `bootDependencies` | 依据 `UpdateReport` 的 compile/runtime 配置生成 GAV 清单文件 |
| `bootRepo` | 将实际存在的非 SNAPSHOT compile/runtime jar 组装成本地 Maven 仓库 |

`bootDependencies` 通过 [CompileHookPlugin](compile-hook.md) 注册为编译后钩子，
产物写入 `Compile / resourceManaged`，随后自动进入 jar。

## 依赖筛选规则

- 仅取 `compile` 与 `runtime` 配置（`optional`、`test`、`provided` 不在其中）；
- 排除被 evict 的模块；
- 排除版本号含 `SNAPSHOT` 的模块；
- 排除自身模块；
- 只保留无 classifier 的主 jar（`jar` / `bundle` 类型）。

## 多模块项目

- 通过 `.dependsOn` 关联的兄弟模块在报告中不带 artifacts（classpath 产物而非仓库 jar）：
  - `bootDependencies` 仍会写出其 GAV，boot 可在发布后自行下载；
  - `bootRepo` 仅在 `exportJars := true` 且 classpath 中存在导出 jar 时才会复制，
    纯 classes 目录不纳入。

## 示例

```bash
sbt bootDependencies   # 生成 target/scala-*/resource_managed/main/META-INF/beangle/dependencies
sbt bootRepo           # 组装 target/repository（Maven 布局）
```

war 项目无需手动调用，启用 [WarPlugin](war.md) 后依赖文件会自动嵌入
`WEB-INF/classes/META-INF/beangle/dependencies`。
