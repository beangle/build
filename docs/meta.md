# MetaPlugin Bean 元数据索引生成

自动启用（`trigger = allRequirements`）；项目中没有声明锚点时自动跳过。

## 用途

把声明文件中的 ORM 映射、CDI 模块与注册器类（`MetaRegistrar` 子类，如
`MappingModule` / `BindModule`）作为契约，生成 `META-INF/beangle/beanmeta.idx` 索引文件。

运行时由 beangle-commons 的 `MetaModels` 通过
`classpath*:META-INF/beangle/beanmeta.idx` 直接加载，避免启动时反射扫描所有类，
同时为 GraalVM native-image 提供确定的元数据入口。

## 锚点文件

- `src/main/resources/META-INF/beangle/meta-registrars.txt`（Compile）
  —— 每行一个 `MetaRegistrar` 实现类名，`#` 开头为注释；
- `src/main/resources/beangle.xml`（Compile）
  —— `<jpa>/<orm>` 的 `<mapping class="...">` 与 `<cdi>` 的 `<module class="...">`；
- 对应 Test 作用域文件（`src/test/resources/...`，可选）。

两类声明合并为一个注册器清单；均缺失或未声明任何类时删除旧产物并跳过。

## 任务

| 任务 | 作用域 | 说明 |
|------|--------|------|
| `metaIndex` | Compile / Test | 生成 `META-INF/beangle/beanmeta.idx` |

通过 [CompileHookPlugin](compile-hook.md) 注册为编译后钩子，产物写入对应
`resourceManaged`，随 jar 打包。声明的类在编译产物中找不到时会报错。

## 机制

fork 子进程 `org.beangle.commons.bean.meta.MetaGenerator`，classpath 为外部依赖 +
依赖项目 classes + 本模块（Test 时含 main）classes；退出码 `2` 短暂重试最多 10 次，
退出码 `1` 立即失败。

## 示例

```bash
sbt "Compile / metaIndex"
# Generated beanmeta.idx at .../resource_managed/main/META-INF/beangle/beanmeta.idx
```

与 GraalVM native-image 配合时，配合 [AotPlugin](aot.md) 一起使用。
