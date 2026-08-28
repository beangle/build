# MetaPlugin Bean 元数据索引生成

自动启用（`trigger = allRequirements`）；项目中没有 `beangle.xml` 时自动跳过。

## 用途

把 `beangle.xml` 中声明的 ORM 映射与 CDI 模块类（`MetaRegistrar` 子类，如
`MappingModule` / `BindModule`）作为契约，生成 `META-INF/beangle/beanmeta.idx` 索引文件。

运行时由 beangle-commons 的 `MetaModels` 通过
`classpath*:META-INF/beangle/beanmeta.idx` 直接加载，避免启动时反射扫描所有类，
同时为 GraalVM native-image 提供确定的元数据入口。

## 锚点文件

- `src/main/resources/beangle.xml`（Compile）；
- `src/test/resources/beangle.xml`（Test，可选）。

`beangle.xml` 缺失或未声明任何 mapping/module 时删除旧产物并跳过。

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
