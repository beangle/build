# AotPlugin GraalVM native-image 配置生成（终端集中式）

手动启用（`trigger = noTrigger`），由**终端项目**（最终 war/native-image 应用）显式：

```scala
.enablePlugins(AotPlugin) // 通常与 MetaPlugin、ProxyPlugin 一起启用
```

库项目不再各自生成配置，只负责在自身资源中携带声明锚点与 registrar 类。

## 用途

在终端项目构建时遍历整个运行时 classpath（本模块 classes/资源目录 + 依赖项目
classes/资源目录 + 外部依赖 jar），读取各条目携带的 `AotHintRegistrar` 声明，
一次性生成合并的 GraalVM native-image 配置，随终端产物打包后在 `native-image`
构建时直接使用。

## 锚点收集（classpath 全量）

每个 classpath 条目（目录或 jar）都会参与收集：

- `META-INF/beangle/aot-registrars.txt`
  —— 每行一个 `org.beangle.commons.aot.AotHintRegistrar` 实现类名，`#` 注释；
- `META-INF/beangle/meta-registrars.txt`
  —— 每行一个 `MetaRegistrar` 实现类名（`MetaRegistrar` 亦为 `AotHintRegistrar`
  子类，bean 元数据注册与 AOT 提示一并汇总）；
- 条目根部的 `beangle.xml`
  —— `<jpa>/<orm>` 的 `<mapping class="...">` 与 `<cdi>` 的 `<module class="...">`
  声明类（均为 `MetaRegistrar` 子类），以及 `<web>` 的 `<initializer class="...">`
  ——不要求是 `AotHintRegistrar`，由 AotHintGenerator 以 `--classes` 清单按名注册到
  `reachability-metadata.json`。

所有条目均无声明时，删除旧的生成产物并跳过，不报错。

## 资源 glob 声明（registrar）

资源 glob 一律由**各库的 registrar** 通过 `registerPattern` 按 glob 语义显式声明
（如 commons 的 `META-INF/services/*`、`beangle.xml`、`**/*.zh_CN`，template 的
`**/*.ftl`），插件不再扫描 classpath 资源后缀自动注册。GraalVM 的
`reachability-metadata.json` 中，不带 `module` 字段的资源 glob 在**整个 classpath**
上匹配，因此任一库声明一次即可覆盖全部依赖 jar 中的同名/同模式资源（`module`
字段才是作用域限定）：

```json
"resources": [
  { "glob": "META-INF/services/*" },
  { "glob": "beangle.xml" },
  { "glob": "**/*.zh_CN" }
]
```

> **glob 不是正则**：`.*\\.xml`、`META-INF/services/.*`、`beangle\\.xml` 这类正则
> 写法在 `glob` 字段匹配不到 `beangle.xml`/服务文件；有效写法为精确名
> （`beangle.xml`）、`*.xml`（单层）或 `**/*.xml`（跨层）。`-H:IncludeResources` 与
> 旧 `resource-config.json` 是正则语义，两者不要混用。（此结论在 GraalVM CE
> 25.3.4.1 实测验证：元数据内嵌于 jar A、资源位于 jar B 时仍能全局命中。）

## 任务

| 任务 | 作用域 | 说明 |
|------|--------|------|
| `aotHints` | Compile | 生成 GraalVM native-image 配置文件 |

通过 [CompileHookPlugin](compile-hook.md) 注册为编译后钩子，产物写入
`Compile / resourceManaged`，随终端产物打包。

## 输出

写入 `META-INF/native-image/beangle/` 子目录：

- `reachability-metadata.json` (GraalVM 25+ 格式，包含所有反射、资源、代理、序列化元数据)
- `native-image.properties` (仅当存在 `registerRuntimeInitialized` 注册时生成)

各依赖库不再需要自带 `META-INF/native-image/` 配置：反射/代理/序列化条目本来就按
类名全局匹配，资源 glob 亦为全局匹配，由终端统一生成一份即可。

## 生成文件来源

| 文件内容 | 注册来源 |
|----------|----------|
| `reflection` 数组 | 各条目 `AotHintRegistrar.registering()` 里的 `registerType`/`registerEnum`；registrar 类自身（普通类注册声明构造器，Scala object 另注册 `$` 伴生类的声明构造器 + 声明字段，保证运行期 `getInstance`/`tryGetInstance` 读 `MODULE$`）；`--classes` 清单的 web initializer（public 构造器 + 探测注册 `$` 伴生类） |
| `resources` 数组 | 各条目 `AotHintRegistrar.registering()` 里的 `registerPattern` 注册的资源模式（glob 语义，见"资源 glob 声明（registrar）"） |
| `reflection` 数组中的代理 | `registerProxy`/`registerProxyByName` 注册的 JDK 动态代理接口 |
| `reflection` 数组中的序列化 | `registerSerializable` 注册的可序列化类 |
| `native-image.properties` | `registerRuntimeInitialized` 注册的类，输出 `--initialize-at-run-time` |

## 机制

- fork 子进程 `org.beangle.commons.aot.AotHintGenerator`，classpath 为整个运行时
  classpath（本模块 + 依赖项目 + 外部依赖）；插件把 registrar 与 classes 两类清单
  分别写入 `target/aot/aot-registrars.txt` 与 `target/aot/aot-classes.txt`，以
  `--registrars`/`--classes` 传给生成器；
- 生成器直接输出 GraalVM 25+ 格式的单一 `reachability-metadata.json` 文件；
- 退出码：`0` 成功、`1` 确定性失败（如声明类不是 registrar）、`2` 声明类未找到
  （sbt 2 的 classDirectory 物化可能晚于编译完成）会短暂重试最多 10 次。

## 清单文件设计

插件不把类名直接作为命令行参数传给生成器，而是先合并出两份清单文件
（`target/aot/aot-registrars.txt`、`target/aot/aot-classes.txt`），再以
`--registrars`/`--classes` 传入。原因如下：

- **多来源合并**：registrars 来自各 classpath 条目的 `aot-registrars.txt`、
  `meta-registrars.txt` 与 `beangle.xml`（jpa/orm mapping、cdi module），classes 由
  各条目的 `<web><initializer>` 提取。插件负责解析、去重、合并成一份纯清单，生成器
  只消费清单本身，双方职责分离。
- **命令行长度**：大型应用的类清单可能数百上千行，Windows 命令行上限 8191 字符、
  Linux 单参数 `MAX_ARG_STRLEN` 128KB，直接传参有截断风险；文件传递不受限制。
- **稳定契约与重试**：sbt 2 下 classDirectory 物化可能晚于编译完成，生成器以退出码
  `2` 报告"声明类未找到"，插件复用同一份清单文件短时重试，清单不随调用变化，
  重试结果确定；清单落在 `target/aot/` 下，随时可查看实际传了哪些类。
- **格式可扩展**：清单文件支持注释与空行，将来如需按行附加注册选项，无需改动
  命令行接口。

## 示例

终端项目（如 ems portal）启用后，编译即聚合生成：

```text
# 依赖 jar 内 META-INF/beangle/aot-registrars.txt（各库自带）
org.beangle.ems.app.BeangleRegistrar
```

```xml
<!-- 依赖 jar 根部 beangle.xml（各库自带） -->
<beangle>
  <jpa>
    <orm>
      <mapping class="org.beangle.ems.app.MappingModule"/>
    </orm>
  </jpa>
  <web>
    <initializer class="org.beangle.she.config.ConfigInitializer"/>
  </web>
</beangle>
```

终端生成一份合并的 `reachability-metadata.json`：

```json
{
  "reflection": [
    { "type": "org.beangle.ems.app.BeangleRegistrar", "allPublicMethods": true, "allPublicConstructors": true },
    { "type": "org.beangle.she.config.ConfigInitializer", "allPublicConstructors": true }
  ],
  "resources": [
    { "glob": "META-INF/services/*" },
    { "glob": "beangle.xml" },
    { "glob": "**/*.zh_CN" }
  ]
}
```
