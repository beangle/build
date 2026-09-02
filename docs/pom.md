# PomPlugin 打包时内嵌 Maven 元信息

自动启用（`trigger = allRequirements`），无需显式配置。

## 用途

与 Maven `mvn package` 的行为对齐：打包时把 Maven 元信息内嵌到产物中，
即 `META-INF/maven/<groupId>/<artifactId>/pom.xml` 与 `pom.properties`，
便于根据 jar/war 直接识别其坐标。

## 行为

- `Compile / packageBin / mappings` 追加两项：`pom.xml`（复用 `makePom` 产物）
  与 `pom.properties`（`groupId` / `artifactId` / `version`）；
- POM 内容与 `publish` 完全一致，同样应用 `pomPostProcess`（如 parent 中
  过滤 test / optional 依赖的规则）与 `pomExtra`；
- jar 项目：元信息位于 jar 根目录 `META-INF/maven/...`；
- war 项目（启用 `WarPlugin`）：经 `packageBin / mappings` 自动落入
  `WEB-INF/classes/META-INF/maven/...`，与 Maven war 布局一致。

## 示例

```bash
sbt package
unzip -l target/.../beangle-commons-6.3.2-SNAPSHOT.jar | grep META-INF/maven
# META-INF/maven/org.beangle.commons/beangle-commons/pom.xml
# META-INF/maven/org.beangle.commons/beangle-commons/pom.properties
```
