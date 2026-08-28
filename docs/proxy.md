# ProxyPlugin Hibernate 懒加载代理生成

自动启用（`trigger = allRequirements`）；锚点条件不满足时自动跳过。

## 用途

在构建期为 Hibernate 实体预生成懒加载代理类，替代运行期 ByteBuddy 动态生成，
使 JVM 与 GraalVM native-image 走同一套预生成类路径，规避反射/动态代理在
native-image 下的限制。

## 触发条件

同时满足以下两个条件才会生成：

1. 存在 `src/main/resources/beangle.xml`（或 Test 作用域对应文件），且
   `<jpa>/<orm>` 下声明了 `<mapping class="...">`；
2. classpath 上存在 `beangle-data-hibernate`（外部 jar、依赖项目或本模块均可）。

否则删除旧的代理产物并跳过，不报错。

## 任务

| 任务 | 作用域 | 说明 |
|------|--------|------|
| `proxyClasses` | Compile / Test | 生成 Hibernate 懒加载代理类与映射清单 |

通过 [CompileHookPlugin](compile-hook.md) 注册为编译后钩子，产物写入对应
`resourceManaged`，随 jar 作为资源打包（`.class` 文件以资源形式加载）。

## 输出

- 生成的代理 `.class` 文件；
- `META-INF/native-image/beangle/data/reflect-config.json`
  —— GraalVM 反射配置片段。

代理类按固定命名约定生成：`<Entity>$HibernateProxy.class`，无需额外的映射清单文件。

## 机制

- fork 子进程 `org.beangle.data.hibernate.aot.BeangleProxyGenerator`；
- 生成器 classpath 会附加构建插件自带的 `net.bytebuddy:byte-buddy` jar——应用运行期
  可以排除 ByteBuddy，不影响构建期生成；
- 退出码 `2`（声明类未找到）短暂重试最多 10 次，退出码 `1` 立即失败；
- 实体集合缩小时会递归清理上次生成的 `*$HibernateProxy.class` 旧代理与残留配置，避免残留。

## 运行时

beangle-data-hibernate 的 `BeangleBytecodeProvider` 按命名约定直接按类名加载
预生成代理，JVM 与 native-image 共用同一路径。

## 示例

```xml
<!-- src/main/resources/beangle.xml -->
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
