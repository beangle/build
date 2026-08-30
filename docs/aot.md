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
  `<web>` 的 `<initializer class="...">`——不要求是 `AotHintRegistrar`，仅以
  `allPublicConstructors` 条目并入 `reflect-config.json`。

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
  依赖项目 classes + 本模块 classes；
- web initializer 类不走 `--registrars` 清单（它们不是 `AotHintRegistrar`，加载会
  失败），生成后由插件以 `{"name":"...","allPublicConstructors":true}` 条目并入
  `reflect-config.json`；仅声明 initializer 时同样会产出 `reflect-config.json`；
- 退出码 `2`（声明类未找到，sbt 2 的 classDirectory 物化可能晚于编译完成）会短暂
  重试最多 10 次；退出码 `1` 立即失败。

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
