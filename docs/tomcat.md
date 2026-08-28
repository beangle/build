# TomcatPlugin 嵌入式 Tomcat 启动

需要显式启用：`.enablePlugins(TomcatPlugin)`（`trigger = noTrigger`）。

## 用途

开发期用嵌入式 Tomcat 直接启动 Web 应用（beangle-sas 引擎），无需安装独立
Tomcat 或部署 war。

## 行为

- 自动添加 test 依赖：
  - `org.beangle.sas:beangle-sas-engine`
  - `org.apache.tomcat.embed:tomcat-embed-core`（11.x）
  - `org.apache.tomcat.embed:tomcat-embed-websocket`
  - `org.slf4j:jul-to-slf4j`（统一日志到 slf4j）

## 任务

| 任务 | 说明 |
|------|------|
| `tomcatStart [args...]` | 用 `Test / fullClasspath` fork 启动 `org.beangle.sas.engine.tomcat.Bootstrap --dev=true`，其余参数原样透传（如端口） |

## 示例

```scala
lazy val myweb = (project in file("web"))
  .enablePlugins(TomcatPlugin)
```

```bash
sbt "web / tomcatStart 8080"
```

开发模式下（`--dev=true`）会直接以 classpath 运行当前模块，改代码后重启即可；
如需 Undertow 容器，改用 [UndertowPlugin](undertow.md)。
