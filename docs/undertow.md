# UndertowPlugin 嵌入式 Undertow 依赖

需要显式启用：`.enablePlugins(UndertowPlugin)`（`trigger = noTrigger`）。

## 用途

为使用 Undertow 嵌入式容器的项目提供测试/开发期依赖，替代 Tomcat。

## 行为

自动添加 test 依赖：

- `org.beangle.sas:beangle-sas-engine`
- `io.undertow.ee:undertow-servlet`
- `org.slf4j:jul-to-slf4j`

本插件不定义任何任务，只负责把容器与日志依赖引入 classpath，应用自行通过
beangle-sas 引擎 API 启动 Undertow。

## 示例

```scala
lazy val myweb = (project in file("web"))
  .enablePlugins(UndertowPlugin)
```

需要 Tomcat 时改用 [TomcatPlugin](tomcat.md)。
