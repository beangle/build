# style plugin


## plugin声明
  使用sbt构建时，在project/plugins.sbt中添加

    addSbtPlugin("org.beangle.build" % "sbt-beangle-build" % "0.0.2")

  该开发包有如下及格插件

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

  在项目的build.sbt中添加如下代码:

    Compile / compile := (Compile / compile).dependsOn(BootPlugin.generateDependenciesTask).value

  可自动生成项目运行时依赖文件/META-INF/beangle/dependencies。如果是war项目则可以省去上述配置，简单的启用war插件即可。

    lazy val myproject = (project in file("."))
       .enablePlugins(WarPlugin)

  包含这种依赖描述文件的jar,可以使用[beangle boot](https://github.com/beangle/boot)进行一键启动。
