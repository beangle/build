# MetaPlugin Bean 元数据索引生成（终端集中式）

手动启用（`trigger = noTrigger`），由**终端项目**（最终应用/需要聚合 bean 元数据的
入口）显式：

```scala
.enablePlugins(MetaPlugin) // 通常与 AotPlugin、ProxyPlugin 一起启用
```

库项目不再各自生成 `beanmeta.idx`，只负责在自身资源中携带声明锚点
（`META-INF/beangle/meta-registrars.txt` 与 `beangle.xml`）。

## 用途

在终端项目构建时遍历整个运行时 classpath（本模块 classes/资源目录 + 依赖项目
classes/资源目录 + 外部依赖 jar），读取各条目携带的 `MetaRegistrar` 声明
（`MetaRegistrar` 子类，如 `MappingModule` / `BindModule`），跨条目合并去重后一次性
生成一份 `META-INF/beangle/beanmeta.idx`，随终端产物打包。

运行时由 beangle-commons 的 `MetaModels` 通过
`classpath*:META-INF/beangle/beanmeta.idx` 直接加载，避免启动时反射扫描所有类，
同时为 GraalVM native-image 提供确定的元数据入口。集中生成后 classpath 上只有终端
产物携带的那一份 idx，即覆盖全部模块的 bean 元数据。

## 锚点收集（classpath 全量）

每个 classpath 条目（目录或 jar）都会参与收集：

- `META-INF/beangle/meta-registrars.txt`
  —— 每行一个 `MetaRegistrar` 实现类名，`#` 开头为注释；
- 条目根部的 `beangle.xml`
  —— `<jpa>/<orm>` 的 `<mapping class="...">` 与 `<cdi>` 的 `<module class="...">`。

两类声明合并为一个注册器清单；classpath 上所有条目均无声明时删除旧产物并跳过，
不报错。声明的类在编译产物中找不到时生成失败（生成器退出码 `2` 短暂重试最多 10
次，退出码 `1` 立即失败）。

## 任务

| 任务 | 作用域 | 说明 |
|------|--------|------|
| `metaIndex` | Compile / Test | 生成合并的 `META-INF/beangle/beanmeta.idx` |

Compile 与 Test 各自独立收集，但**声明的来源范围不同**：Compile 扫描整个运行时
classpath；Test 只在本模块 test classes/资源里找声明（生成用的 classpath 仍并入 main
的 classes/资源与 Test 外部依赖，供测试运行期使用）。所以 Test 的产物只含 test 声明的
类，没有 test 声明时不产出文件。

这一点很关键：Test 的 classpath 本来就包含全部主产物，如果声明也照单全收，产出就是主
索引的等价副本。而它只在 Test 编译时刷新，主代码演进后不会跟着更新，却会在运行期覆盖
主索引（`MetaModels` 合并 `classpath*:` 上所有 idx 时，同一类取后者，于是读到的是 test
目录里那份旧的）。

通过
[CompileHookPlugin](compile-hook.md) 注册为编译后钩子，产物写入对应
`resourceManaged`，随产物打包。

## 机制

fork 子进程 `org.beangle.commons.bean.meta.MetaGenerator`，classpath 为整个运行时
classpath（本模块 + 依赖项目 + 外部依赖）；插件先把跨条目解析、去重后的 registrar
清单写入 `target/meta/beanmeta-registrars.txt`，以 `--registrars` 传给生成器。

## 迁移说明

- **库项目**：不再启用 `MetaPlugin`、不再随 jar 产出 `beanmeta.idx`，只保留
  `meta-registrars.txt` / `beangle.xml` 声明。发布前确认新版本 jar 不再携带旧的
  `META-INF/beangle/beanmeta.idx`。
- **终端项目**（最终应用）：显式 `.enablePlugins(MetaPlugin)`；缺少启用时应用
  classpath 上不会出现 idx。

## 示例

```bash
sbt "Compile / metaIndex"
# Generated beanmeta.idx at .../resource_managed/main/META-INF/beangle/beanmeta.idx using 1110 ms

sbt "Test / metaIndex"
# 有 Test 声明：Generated beanmeta.idx at .../resource_managed/test/META-INF/beangle/beanmeta.idx using 480 ms
# 没有则跳过且不留文件（残留的旧产物会被删除）
```

与 GraalVM native-image 配合时，与 [AotPlugin](aot.md) 一起启用。
