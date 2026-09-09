# 插件文档

各插件的用途总览、引入方式与典型组合见根目录 [README](../README.md)。本目录是各插件的
详细说明（任务、设置、锚点文件与使用示例）。

## 自动启用插件

| 插件 | 用途 | 文档 |
|------|------|------|
| StylePlugin | 源码格式检查与格式化（空白 + 许可证头） | [style.md](style.md) |
| StatPlugin | 按扩展名统计代码行数 | [stat.md](stat.md) |
| PomPlugin | 打包时内嵌 Maven 元信息（META-INF/maven） | [pom.md](pom.md) |
| BootPlugin | 生成 beangle-boot 运行时依赖元数据 | [boot.md](boot.md) |
| CompileHookPlugin | 统一提供编译前/后钩子扩展点 | [compile-hook.md](compile-hook.md) |
| DdlPlugin | 生成 DDL 报告与版本间 DDL diff | [ddl.md](ddl.md) |
| OrmPlugin | 从 ORM 映射生成建表 DDL | [orm.md](orm.md) |

## 手动启用插件

| 插件 | 用途 | 文档 |
|------|------|------|
| MetaPlugin | 聚合全 classpath 生成 bean 元数据索引 `beanmeta.idx`（终端） | [meta.md](meta.md) |
| AotPlugin | 聚合全 classpath 生成 GraalVM native-image 配置（终端） | [aot.md](aot.md) |
| ProxyPlugin | 聚合全 classpath 生成 Hibernate 懒加载代理类（终端） | [proxy.md](proxy.md) |
| WarPlugin | Web 应用 war 打包与增量 diff | [war.md](war.md) |
| SnapshotPlugin | 快照版本 war/jar 构建与上传 | [snapshot.md](snapshot.md) |
| TomcatPlugin | 开发期启动嵌入式 Tomcat | [tomcat.md](tomcat.md) |
| UndertowPlugin | 提供嵌入式 Undertow 测试依赖 | [undertow.md](undertow.md) |

> MetaPlugin、AotPlugin、ProxyPlugin 由终端项目（最终应用）显式启用，扫描整个运行时
> classpath（本模块 + 依赖项目 + 外部依赖 jar）集中生成；库项目只携带声明锚点。

## 参考备忘

- [GraalVM 25+ reachability-metadata.json 格式备忘](graalvm-reachability-metadata.md)
  —— 统一可达性元数据的字段级参考（schema v1.2.0，GraalVM 25+），AotPlugin /
  ProxyPlugin 产物的格式依据。

## 非插件工具

源码中还有一组不暴露为 sbt 任务的内部工具，供插件自身使用：

- `org.beangle.build.util`：文件、IO、压缩、BSDiff 等通用工具。
- `org.beangle.build.downloader`：HTTP/HTTPS 下载工具。
- `org.beangle.build.stat` / `org.beangle.build.style`：行数统计与格式检查/格式化核心逻辑。
