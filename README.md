# Beangle Build Tools

Beangle 系列项目使用的 sbt 构建插件集合：格式检查、代码统计、运行时依赖元数据、
war 打包、GraalVM native-image 配置、ORM/DDL 生成与嵌入式容器启动等。

> **版本兼容：** 从 0.1.0 起仅支持 sbt 2.x（artifact 后缀为 `_sbt2_3`），要求 JDK 17+；
> sbt 1.x 项目请继续使用 0.0.x 版本。各插件详细说明见 [docs/](docs/README.md)。

## 引入

在 `project/plugins.sbt` 中添加：

```scala
addSbtPlugin("org.beangle.build" % "sbt-beangle-build" % "0.1.4-SNAPSHOT")
```

并在 `project/build.properties` 中设置：

```properties
sbt.version=2.0.3
```

## 插件一览

| 插件 | 启用方式 | 用途 | 文档 |
|------|----------|------|------|
| [StylePlugin](docs/style.md) | 自动 | 源码格式检查与格式化（空白 + 许可证头） | [style.md](docs/style.md) |
| [StatPlugin](docs/stat.md) | 自动 | 按扩展名统计代码行数 | [stat.md](docs/stat.md) |
| [BootPlugin](docs/boot.md) | 自动 | 生成 beangle-boot 运行时依赖元数据 | [boot.md](docs/boot.md) |
| [CompileHookPlugin](docs/compile-hook.md) | 自动 | 统一提供编译前/后钩子扩展点 | [compile-hook.md](docs/compile-hook.md) |
| [MetaPlugin](docs/meta.md) | 自动 | 生成 bean 元数据索引 `beanmeta.idx` | [meta.md](docs/meta.md) |
| [AotPlugin](docs/aot.md) | 自动 | 生成 GraalVM native-image 配置文件 | [aot.md](docs/aot.md) |
| [ProxyPlugin](docs/proxy.md) | 自动 | 生成 Hibernate 懒加载代理类 | [proxy.md](docs/proxy.md) |
| [DdlPlugin](docs/ddl.md) | 自动 | 生成 DDL 报告与版本间 DDL diff | [ddl.md](docs/ddl.md) |
| [OrmPlugin](docs/orm.md) | 自动 | 从 ORM 映射生成建表 DDL | [orm.md](docs/orm.md) |
| [WarPlugin](docs/war.md) | 手动 | Web 应用 war 打包与增量 diff | [war.md](docs/war.md) |
| [SnapshotPlugin](docs/snapshot.md) | 手动 | 快照版本 war/jar 构建与上传 | [snapshot.md](docs/snapshot.md) |
| [TomcatPlugin](docs/tomcat.md) | 手动 | 开发期启动嵌入式 Tomcat | [tomcat.md](docs/tomcat.md) |
| [UndertowPlugin](docs/undertow.md) | 手动 | 提供嵌入式 Undertow 测试依赖 | [undertow.md](docs/undertow.md) |

> 自动启用：`trigger = allRequirements`，引入插件后即生效；多数生成器在没有对应锚点
> 文件（如 `beangle.xml`）时自动跳过，无需显式配置。手动插件需 `.enablePlugins(...)`。

## 典型组合

- **普通库项目**：StylePlugin（编译前检查）+ StatPlugin + BootPlugin + MetaPlugin
  （有 `beangle.xml` 时自动生成 `beanmeta.idx`）。
- **Web 应用项目**：显式启用 `WarPlugin`，依赖清单自动嵌入
  `WEB-INF/classes/META-INF/beangle/dependencies`，可用
  [beangle boot](https://github.com/beangle/boot) 一键启动。
- **GraalVM native-image**：AotPlugin 自动收集 `AotHintRegistrar` 生成配置，
  配合 MetaPlugin、ProxyPlugin 的产物一起打包。
- **ORM 项目**：OrmPlugin 生成多数据库建表 DDL，DdlPlugin 生成 SQL 报告与迁移脚本。

## 文档

各插件的任务、设置与使用示例见 [docs/](docs/README.md)。
