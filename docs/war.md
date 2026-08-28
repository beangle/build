# WarPlugin Web 应用打包

需要显式启用：`.enablePlugins(WarPlugin)`；依赖 BootPlugin。

## 用途

提供 war 打包、SNAPSHOT 依赖内嵌、默认 `web.xml` 补齐以及版本间增量 diff：

- 将 `Compile / package` 的产物类型改为 `war`；
- 组装 `src/main/webapp` 下的静态资源与 `WEB-INF` 结构；
- 把编译产物放入 `WEB-INF/classes`，依赖 jar 放入 `WEB-INF/lib`；
- 自动嵌入 BootPlugin 生成的依赖清单文件；
- 对 SNAPSHOT 版本项目做特殊处理，避免打包结果依赖开发期目录类。

## 任务与设置

| 键 | 类型 | 说明 |
|----|------|------|
| `warPrepare` | 任务 | 组装 webapp 打包内容 |
| `warAddDefaultWebxml` | 设置，默认 `true` | 缺少 `web.xml` 时自动补充默认模板 |
| `warDiff oldVersion [newVersion]` | 输入任务 | 基于本地 m2 仓库中两个版本 war 生成 BSDiff 增量补丁 |

## 打包布局

- 静态资源：`src/main/webapp/**`（`warPrepare / sourceDirectory` 默认
  `(Compile / sourceDirectory) / "webapp"`，即 `src/main/webapp`）；
- 编译产物：`WEB-INF/classes`；
- 依赖 jar：`WEB-INF/lib`（SNAPSHOT 版本项目会把依赖项目的 classes 目录 jar 化后放入，
  并单独复制所有 SNAPSHOT jar）；
- 依赖清单：BootPlugin 生成的 `dependencies` 文件复制到
  `WEB-INF/classes/META-INF/beangle/dependencies`；
- Manifest：自动附加 `Bundle-SymbolicName`、`Bundle-Version`、`Specification-*`、
  `Implementation-*`、`Build-Scala-Spec`、`Bundle-License` 等属性。

## 增量 diff

`warDiff` 从配置的 Maven 仓库读取新旧两个 war，用 `Bsdiff` 生成增量补丁并复制到
`crossTarget`，用于应用的热升级场景：

```bash
sbt "warDiff 1.0.0 1.0.1"
```

## 示例

```scala
lazy val myproject = (project in file("."))
  .enablePlugins(WarPlugin)
  .settings(
    name := "beangle-ems-app",
    Compile / package / artifact := Artifact("beangle-ems-app", "war", "war")
  )
```

```bash
sbt package   # 生成 beangle-ems-app-<version>.war
```
