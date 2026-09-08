# AotPlugin GraalVM native-image 配置生成

自动启用（`trigger = allRequirements`）；项目中没有 AOT 声明时自动跳过，无需显式关闭。

## 用途

从 `AotHintRegistrar` 实现收集反射、资源、代理、序列化等注册信息，生成 GraalVM
native-image 需要的配置文件，随 jar 打包后在 `native-image` 构建时直接使用。

## 锚点文件

三类声明都会参与，合并为一个注册器清单：

- `src/main/resources/META-INF/beangle/aot-registrars.txt`
  —— 每行一个 `org.beangle.commons.aot.AotHintRegistrar` 实现类名，`#` 开头为注释；
- `src/main/resources/META-INF/beangle/meta-registrars.txt`
  —— 每行一个 `MetaRegistrar` 实现类名（`MetaRegistrar` 亦为 `AotHintRegistrar`
  子类，bean 元数据注册与 AOT 提示一并汇总），`#` 开头为注释；
- `src/main/resources/beangle.xml`
  —— `<jpa>/<orm>` 的 `<mapping class="...">` 与 `<cdi>` 的 `<module class="...">`
  声明的类（均为 `MetaRegistrar` 子类，由 MetaRegistrar 汇总 AOT 提示），以及
  `<web>` 的 `<initializer class="...">`——不要求是 `AotHintRegistrar`，由
  AotHintGenerator 以 `--classes` 清单按名注册到 `reachability-metadata.json`。

registrar 与 classes（如 web initializer）均无声明时，删除旧的生成产物并跳过，不报错。

## 任务

| 任务 | 作用域 | 说明 |
|------|--------|------|
| `aotHints` | Compile | 生成 GraalVM native-image 配置文件 |

通过 [CompileHookPlugin](compile-hook.md) 注册为编译后钩子，产物写入
`Compile / resourceManaged`，随 jar 打包。

## 输出

写入 `META-INF/native-image/beangle/` 子目录（每个框架/插件独占一个子目录，
多库共存时配置自动合并）：

- `reachability-metadata.json` (GraalVM 25+ 格式，包含所有反射、资源、代理、序列化元数据)
- `native-image.properties` (仅当存在 `registerRuntimeInitialized` 注册时生成)

**旧格式（已废弃）**：之前生成多个独立文件（`reflect-config.json`、`resource-config.json`、
`proxy-config.json`、`serialization-config.json`），现在统一合并为单一的
`reachability-metadata.json` 文件，符合 GraalVM 25 的可达性元数据规范。

每个类别存在注册项时才写出对应内容；类别为空时删除残留旧文件，避免把失效配置
打包进 jar。各依赖库（如 commons）自带的 `META-INF/native-image/beangle/` 配置
由 native-image 构建时自动合并，插件只生成本模块声明来源的注册项。

## 生成文件来源

| 文件内容 | 注册来源 |
|----------|----------|
| `reflection` 数组 | `AotHintRegistrar.registering()` 里的 `registerType`/`registerEnum`；registrar 类自身（普通类注册声明构造器，Scala object 另注册 `$` 伴生类的声明构造器 + 声明字段，保证运行期 `getInstance`/`tryGetInstance` 读 `MODULE$`）；`--classes` 清单的 web initializer（public 构造器 + 探测注册 `$` 伴生类） |
| `resources` 数组 | `registerPattern` 注册的资源模式，例如 commons 内置 registrar 的 `META-INF/services/.*`、`beangle.xml`、`.*\.zh_CN`、mime 类型表，`MetaAotHints` 的 `META-INF/beangle/beanmeta.idx`，`LogbackAotHints` 的 `logback.xml` |
| `reflection` 数组中的代理 | `registerProxy`/`registerProxyByName` 注册的 JDK 动态代理接口 |
| `reflection` 数组中的序列化 | `registerSerializable` 注册的可序列化类（`registerEnum` 会同步登记枚举值类的序列化） |
| `native-image.properties` | `registerRuntimeInitialized` 注册的类，输出 `--initialize-at-run-time` |

示例：bui 的 `BuiMetaRegistrar` 只调用 `register`/`registerType`，因此仅产出
`reflection` 内容；资源、代理、序列化类别为空时不生成对应内容。

## 机制

- fork 子进程 `org.beangle.commons.aot.AotHintGenerator`，classpath 为外部依赖 +
  依赖项目 classes + 本模块 classes；插件把 registrar 与 classes 两类清单分别写入
  `target/aot/aot-registrars.txt` 与 `target/aot/aot-classes.txt`，以
  `--registrars`/`--classes` 传给生成器；
- 使用 `--format reachability` 参数生成 GraalVM 25+ 格式的单一
  `reachability-metadata.json` 文件；
- `--registrars` 清单：每个类必须是 `AotHintRegistrar` 实现。生成器加载后调用
  `registering()` 收集反射/资源/代理/序列化提示，并把 registrar 类自身注册进
  `reachability-metadata.json`（普通类注册声明构造器；Scala object 还注册 `$` 伴生类的
  声明构造器 + 声明字段，保证运行期 `getInstance`/`tryGetInstance` 能读 `MODULE$`）；
- `--classes` 清单：web initializer 等按名加载类，不要求是 `AotHintRegistrar`。
  主类注册 `allPublicConstructors`（运行期经 `getDeclaredConstructor().newInstance()`
  实例化）；由于用户声明的类名不带 `$`、实际可能是 Scala object，生成器同时探测并
  注册 `$` 伴生类（声明构造器 + 声明字段，`MODULE$` 单例入口）。仅声明 classes
  时同样会产出 `reflection` 内容；两类皆声明时合并写入同一份配置；
- 按类别写出：`registering()` 收集的 `AotHints` 分五类（反射类型/资源模式/代理/
  序列化/运行期初始化），类别为空时对应内容不生成（见"生成文件来源"）；
- 退出码：`0` 成功、`1` 确定性失败（如声明类不是 registrar）、`2` 声明类未找到
  （sbt 2 的 classDirectory 物化可能晚于编译完成）会短暂重试最多 10 次。

## 清单文件设计

插件不把类名直接作为命令行参数传给生成器，而是先合并出两份清单文件
（`target/aot/aot-registrars.txt`、`target/aot/aot-classes.txt`），再以
`--registrars`/`--classes` 传入。原因如下：

- **多来源合并**：registrars 由 `aot-registrars.txt`、`meta-registrars.txt` 与
  `beangle.xml`（jpa/orm mapping、cdi module）合并，classes 由 `<web><initializer>` 提取。
  插件负责解析、去重、合并成一份纯清单，生成器只消费清单本身，双方职责分离；
  将来新增声明来源（如其他模块的按名加载类）只需改插件侧合并逻辑，生成器不变。
- **命令行长度**：大型应用的类清单可能数百上千行，Windows 命令行上限 8191 字符、
  Linux 单参数 `MAX_ARG_STRLEN` 128KB，直接传参有截断风险；文件传递不受限制。
- **稳定契约与重试**：sbt 2 下 classDirectory 物化可能晚于编译完成，生成器以退出码
  `2` 报告"声明类未找到"，插件复用同一份清单文件短时重试，清单不随调用变化，
  重试结果确定；清单落在 `target/aot/` 下，随时可查看实际传了哪些类。
- **与既有模式一致**：`aot-registrars.txt` 本身就是每行一类、支持 `#` 注释的
  锚点文件，生成器最初即文件清单模式；MetaPlugin（`beanmeta-registrars.txt`）、
  ProxyPlugin（`proxy-registrars.txt`）同样以文件传清单，各生成器保持一致。
- **格式可扩展**：清单文件支持注释与空行，将来如需按行附加注册选项（如自定义
  反射策略），无需改动命令行接口。

作为对照，classpath 条目是数量有限的扁平路径列表，直接作为尾随参数传递即可。

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

## 生成的 reachability-metadata.json 示例

```json
{
  "reflection": [
    {
      "type": "org.beangle.ems.app.BeangleRegistrar",
      "allPublicMethods": true,
      "allPublicConstructors": true
    },
    {
      "type": "org.beangle.ems.app.MappingModule",
      "allPublicMethods": true,
      "allPublicConstructors": true
    },
    {
      "type": {
        "proxy": ["java.io.Serializable", "org.beangle.commons.i18n.Locale"]
      }
    },
    {
      "type": "org.beangle.ems.model.User",
      "serializable": true
    }
  ],
  "resources": [
    {
      "glob": "META-INF/services/.*"
    },
    {
      "glob": "beangle.xml"
    },
    {
      "glob": ".*\\.zh_CN"
    },
    {
      "glob": "META-INF/beangle/beanmeta.idx"
    }
  ]
}
```
