# Beangle Build Tools

## 引入 sbt plugin

使用 sbt 2.x 构建时，在 `project/plugins.sbt` 中添加：

    addSbtPlugin("org.beangle.build" % "sbt-beangle-build" % "0.1.0-SNAPSHOT")

并在 `project/build.properties` 中设置：

    sbt.version=2.0.3

> **注意：** 从 0.1.0 起仅支持 sbt 2.x（artifact 后缀为 `_sbt2_3`）。sbt 1.x 项目请继续使用 0.0.x 版本。

该开发包有如下几个插件：

### 1. StylePlugin 格式检查

  在编译过程中，会自动执行styleCheck任务，检查以下规则。

 检查和格式化源代码中的空白元素，使之符合如下要求：

1. 使用空格代替tab缩进
2. 每行源代码不能使用空格结尾
3. 每个源文件需要使用空行结尾
4. 源文件需要在头部声明许可证

手工格式化代码，可以使用

    styleFormat

### 2. StatPlugin 代码行数统计

    statLoc

可以统计各种类型的文件其中的代码行数。

### 3. BootPlugin 生成运行时依赖文件

  BootPlugin 默认启用：通过 `resourceGenerators` 根据 `UpdateReport` 的
  `compile`/`runtime` 配置自动生成 `/META-INF/beangle/dependencies`
  （排除 SNAPSHOT 与 optional）。
  也可手动执行 `bootDependencies` / `bootRepo`。War 项目启用 WarPlugin 即可。

    lazy val myproject = (project in file("."))
       .enablePlugins(WarPlugin)

  包含这种依赖描述文件的jar,可以使用[beangle boot](https://github.com/beangle/boot)进行一键启动。
