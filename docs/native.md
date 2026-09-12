# NativeImagePlugin 原生镜像构建

需要显式启用：`.enablePlugins(NativeImagePlugin)`（`trigger = noTrigger`）。

## 用途

定位已安装的 `native-image`，把项目链接成原生可执行文件，并打包成可分发的 `tar.gz`。

## 任务与设置

| 键 | 类型 | 说明 |
|----|------|------|
| `nativeImageCommand` | 任务 | 启动 native-image 的命令，按 `GRAAL_HOME` / `GRAALVM_HOME` / `JAVA_HOME` 查找 `bin/native-image` |
| `nativeImageOptions` | 任务 | 传给 native-image 的附加参数 |
| `nativeImageOutput` | 任务 | 可执行文件路径，默认 `(NativeImage / target)/<name>` |
| `nativeImage` | 任务 | 链接出原生可执行文件 |
| `nativeDist` | 任务 | 打包成 `<name>-<version>-<classifier>.tar.gz`，并生成 `.sha1` 与 `.sha256` |
| `nativeDistClassifier` | 设置 | 文件名里的平台标签，默认由 `os.name` / `os.arch` 推导，如 `linux-amd64` |
| `nativeDistInstall` | 任务 | 把归档和 pom 安装到本地 maven 仓库 |
| `nativeDistLocalRepo` | 设置，默认 `~/.m2/repository` | 本地 maven 仓库根目录 |
| `nativeDistDelta` | 任务 | 以本地仓库中上一版本的发行包为基准生成 bsdiff 增量补丁 |
| `nativeDistDeltaBase` | 设置，默认 `None` | 增量基准版本，缺省取小于当前版本的最大 git tag |
| `nativeDistDelta` / `nativeDeltaUpload` | 任务 | 生成增量补丁 / 上传本版本校验和与补丁 |
| `nativeDeltaRepoUrl` | 设置，默认 `"unknown-url"` | 增量上传地址，支持 `{fileName}` 占位符 |

`NativeImage / target` 默认为 `<项目>/target/native-image`。

## 行为

- 全部参数写入 `@native-image.args`：classpath 条目先 realpath 归一化（sbt 2 的 CAS
  让产物 jar 以符号链接形式出现，同一文件会以两条 URL 注册），写盘前后各校验一次无重复条目，
  重复即构建失败；`native-image` 以该参数文件为唯一输入，避免 `-cp` 过长；
- `nativeDist` 归档内容 = 可执行文件 + native-image 生成的 `lib*.so`。这些 `.so` 是运行期由
  JNI 动态加载的（AWT/freetype 等），不是链接进可执行文件的，必须随包；`native-image.args`
  等中间产物不入包；
- 归档固定使用 gzip：目标机上系统 `tar` 即可 `tar xzf` 解开（CentOS 8 的 tar 1.30 不支持
  `--zstd`，zstd 命令行也不是默认安装），Java 侧用内置 `GZIPInputStream`，不引入额外依赖；
- `nativeDistInstall` 按 maven 布局写入 `<repo>/<group 转路径>/<artifactId>/<version>/`，
  包含归档、pom 以及各自的 `.sha1`，供 maven 本地解析与增量构建取用。

## 增量分发

`nativeDistDelta` 复用 `WarPlugin.warDiff` 同一套约定（`org.beangle.build.util.Bsdiff` +
`Dependency.m2Path/m2DiffPath`）：从本地 maven 仓库取出上一版本的发行包，与当前发行包做
bsdiff，补丁落在 `<m2>/<group>/<name>/<新版本>/<name>-<旧版本>_<新版本>-<classifier>.tar.gz.diff`，
并复制一份到发行目录，同时生成 `.sha1`。

- 基准版本：`nativeDistDeltaBase`，缺省取 `git tag --sort=-v:refname` 中小于当前版本的
  最大 tag（不是 `git describe`：发布 tag 常常打在别的分支的合并提交上，不是祖先）；
- 比对的是**解压后的 tar**。压缩流之间做 bsdiff 出来的补丁几乎等于整包
  （实测 41.5M / 41.8M，等于没有任何节省），所以两端都先 gunzip 再比对；
- bsdiff 会把两个文件整体读进内存并建后缀数组，150MB 级别需要约 3GB 堆：
  `sbt -J-Xmx4G nativeDistDelta`，堆不够时会打印告警；
- 客户端升级流程：保留上一版发行包 → 解出旧 tar → `Bsdiff.patch` 得到新 tar → 解压部署；
- `nativeDeltaUpload` 依赖 `nativeDistDelta`（会现场重算补丁），上传本版本的 `.sha1`、
  补丁及其 `.sha1`，地址取 `nativeDeltaRepoUrl`，凭据复用
  `~/.sbt/snapshot_credentials`（同 SnapshotPlugin）。

## 示例

```scala
lazy val portal = (project in file("portal"))
  .enablePlugins(NativeImagePlugin)
  .settings(
    Compile / mainClass := Some("org.beangle.sas.engine.tomcat.Bootstrap"),
    nativeImageOptions ++= Seq("-Os", "--no-fallback")
  )
```

```bash
sbt nativeDist nativeDistInstall
# target/native-image/                      ← 可执行文件 + lib*.so
# target/<name>-<version>-linux-amd64.tar.gz(.sha1/.sha256)
# ~/.m2/repository/<group>/<name>/<version>/<name>-<version>-linux-amd64.tar.gz

sbt -J-Xmx4G "nativeDistDelta" "nativeDeltaUpload"
# ~/.m2/repository/<group>/<name>/<version>/<name>-<old>_<version>-linux-amd64.tar.gz.diff
# 上传：<name>-<version>-linux-amd64.tar.gz.sha1、上述 .diff 及其 .sha1
```
