# ProxyPlugin Hibernate 懒加载代理生成（终端集中式）

手动启用（`trigger = noTrigger`），由**终端项目**（最终应用，通常是 war /
native-image 应用）显式：

```scala
.enablePlugins(ProxyPlugin) // 通常与 MetaPlugin、AotPlugin 一起启用
```

库项目不再各自生成代理，只负责在自身资源中携带 `beangle.xml`（`<jpa>/<orm>`
mapping 声明）。

## 用途

在构建期为 Hibernate 实体预生成懒加载代理类，替代运行期 ByteBuddy 动态生成，使
JVM 与 GraalVM native-image 走同一套预生成类路径，规避反射/动态代理在 native-image
下的限制。

生成是"集中式"的：终端项目构建时遍历整个运行时 classpath（本模块 classes/资源目录
+ 依赖项目 classes/资源目录 + 外部依赖 jar），合并所有条目 `beangle.xml` 中
`<jpa>/<orm><mapping class="...">` 声明的实体，一次性为它们生成代理类，随终端产物
打包。代理类按全限定名 + `$HibernateProxy` 命名约定由运行期加载，与实体类是否在同一
jar 无关，因此跨 jar 的实体也能被终端产物的代理覆盖。

## 触发条件

同时满足以下两个条件才会生成：

1. classpath 上（本模块或任一依赖条目）存在 `beangle.xml`，且 `<jpa>/<orm>` 下
   声明了 `<mapping class="...">`；
2. classpath 上存在 `beangle-data-hibernate`（外部 jar、依赖项目或本模块均可）。

否则删除旧的代理产物并跳过，不报错。

## 任务

| 任务 | 作用域 | 说明 |
|------|--------|------|
| `proxyClasses` | Compile / Test | 生成 Hibernate 懒加载代理类与映射清单 |

Compile 与 Test 各自独立收集：Test 范围在 Test classes/资源之外，还会并入 main 的
classes/资源与 Test 外部依赖。通过 [CompileHookPlugin](compile-hook.md) 注册为编译后
钩子，产物写入对应 `resourceManaged`，随产物作为资源打包（`.class` 文件以资源形式
加载）。

## 输出

- 生成的代理 `.class` 文件；
- `META-INF/native-image/beangle/data/reachability-metadata.json`
  —— GraalVM 25+ 统一可达性元数据片段。顶层对象含 `reflection` 数组，按固定命名
  约定为每个 `<Entity>$HibernateProxy` 注册：

  ```json
  { "reflection": [
      { "type": "org.example.Student$HibernateProxy", "allPublicMethods": true,
        "methods": [
          { "name": "<init>", "parameterTypes": [] },
          { "name": "writeReplace", "parameterTypes": [] } ] }
  ] }
  ```

  native-image 构建时把 classpath 上 `META-INF/native-image/` 下各片段（本文件与
  AotPlugin 合并生成的全局片段）自动合并读取，无需构建参数。
  字段要求与旧格式差异见 [graalvm-reachability-metadata.md](graalvm-reachability-metadata.md)。

代理类按固定命名约定生成：`<Entity>$HibernateProxy.class`，无需额外的映射清单文件。

## 机制

- fork 子进程 `org.beangle.data.hibernate.proxy.BeangleProxyGenerator`；
- 生成器 classpath 会附加构建插件自带的 `net.bytebuddy:byte-buddy` jar——应用运行期
  可以排除 ByteBuddy，不影响构建期生成；
- 插件先把跨条目收集的 mapping 实体写入 `target/proxy/proxy-registrars.txt` 传给
  生成器；生成器 classpath = 整个运行时 classpath（本模块 + 依赖项目 + 外部依赖）；
- 退出码 `2`（声明类未找到）短暂重试最多 10 次，退出码 `1` 立即失败；
- 实体集合缩小时会递归清理上次生成的 `*$HibernateProxy.class` 旧代理与残留配置，避免残留。

## 运行时

beangle-data-hibernate 的 `PrebuiltProxyProvider` 按命名约定直接按类名加载
预生成代理，JVM 与 native-image 共用同一路径。

## 迁移说明

- **库项目**：不再启用 `ProxyPlugin`、不再随 jar 产出代理类与
  reflect-config 片段（旧文件会被清理），只保留 `beangle.xml` 声明。
- **终端项目**（最终应用）：显式 `.enablePlugins(ProxyPlugin)`；缺省时实体在运行期
  退回 ByteBuddy 动态生成（JVM 可用，native-image 需要另外的反射/代理配置）。

## 示例

```xml
<!-- 依赖 jar 根部 beangle.xml（各库自带） -->
<beangle>
  <jpa>
    <orm>
      <mapping class="org.beangle.ems.app.MappingModule"/>
    </orm>
  </jpa>
</beangle>
```

```bash
sbt "Compile / proxyClasses"
```
