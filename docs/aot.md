# AotPlugin GraalVM native-image 配置生成

自动启用（`trigger = allRequirements`）；项目中没有 AOT 声明时自动跳过，无需显式关闭。

## 用途

从 `AotHintRegistrar` 实现收集反射、资源、代理、序列化等注册信息，生成 GraalVM
native-image 需要的配置文件，随 jar 打包后在 `native-image` 构建时直接使用。

## 锚点文件

两类声明都会参与，合并为一个注册器清单：

- `src/main/resources/META-INF/beangle/aot-registrars.txt`
  —— 每行一个 `org.beangle.commons.aot.AotHintRegistrar` 实现类名，`#` 开头为注释；
- `src/main/resources/beangle.xml`
  —— `<jpa>/<orm>` 的 `<mapping class="...">` 与 `<cdi>` 的 `<module class="...">`
  声明的类（均为 `MetaRegistrar` 子类，由 MetaRegistrar 汇总 AOT 提示），以及
  `<web>` 的 `<initializer class="...">`——不要求是 `AotHintRegistrar`，由
  AotHintGenerator 以 `--initializers` 清单按名注册到 `reflect-config.json`。

registrar 与 initializer 均无声明时，删除旧的生成产物并跳过，不报错。

## 任务

| 任务 | 作用域 | 说明 |
|------|--------|------|
| `aotHints` | Compile | 生成 GraalVM native-image 配置文件 |

通过 [CompileHookPlugin](compile-hook.md) 注册为编译后钩子，产物写入
`Compile / resourceManaged`，随 jar 打包。

## 输出

写入 `META-INF/native-image/beangle/` 子目录（每个框架/插件独占一个子目录，
多库共存时配置自动合并）：

- `reflect-config.json`
- `resource-config.json`
- `proxy-config.json`
- `serialization-config.json`
- `native-image.properties`

## 机制

- fork 子进程 `org.beangle.commons.aot.AotHintGenerator`，classpath 为外部依赖 +
  依赖项目 classes + 本模块 classes；插件把两类清单分别写入
  `target/aot/aot-registrars.txt` 与 `target/aot/aot-initializers.txt`，以
  `--registrars`/`--initializers` 传给生成器；
- `--registrars` 清单：每个类必须是 `AotHintRegistrar` 实现。生成器加载后调用
  `registering()` 收集反射/资源/代理/序列化提示，并把 registrar 类自身注册进
  `reflect-config.json`（普通类注册声明构造器；Scala object 还注册 `$` 伴生类的
  声明构造器 + 声明字段，保证运行期 `getInstance`/`tryGetInstance` 能读 `MODULE$`）；
- `--initializers` 清单：web initializer 等按名加载类，不要求是 `AotHintRegistrar`。
  主类注册 `allPublicConstructors`（运行期经 `getDeclaredConstructor().newInstance()`
  实例化）；由于用户声明的类名不带 `$`、实际可能是 Scala object，生成器同时探测并
  注册 `$` 伴生类（声明构造器 + 声明字段，`MODULE$` 单例入口）。仅声明 initializer
  时同样会产出 `reflect-config.json`；两类皆声明时合并写入同一份配置；
- 退出码：`0` 成功、`1` 确定性失败（如声明类不是 registrar）、`2` 声明类未找到
  （sbt 2 的 classDirectory 物化可能晚于编译完成）会短暂重试最多 10 次。

## 示例

```text
# src/main/resources/META-INF/beangle/aot-registrars.txt
org.beangle.ems.app.BeangleRegistrar
```

```xml
<!-- src/main/resources/beangle.xml -->
<beangle>
  <jpa>
    <orm>
      <mapping class="org.beangle.ems.app.MappingModule"/>
    </orm>
  </jpa>
  <cdi>
    <module class="org.beangle.ems.app.CdiModule"/>
  </cdi>
  <web>
    <initializer class="org.beangle.she.config.ConfigInitializer"/>
    <initializer class="org.beangle.she.spring.ContainerInitializer"/>
    <initializer class="org.beangle.she.webmvc.WebmvcInitializer"/>
    <initializer class="org.beangle.she.config.CleanupInitializer"/>
  </web>
</beangle>
```
