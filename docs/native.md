# NativeImagePlugin 原生镜像构建与分发

需要显式启用：`.enablePlugins(NativeImagePlugin)`（`trigger = noTrigger`）。

## 用途

定位已安装的 `native-image`，把项目链接成原生可执行文件、打包成可分发的 `tar.gz`，
并按需生成增量补丁、把整包/补丁发布到 native 仓库。

## 任务

构建与打包（一次完成）：

| 键 | 说明 |
|----|------|
| `nativeImage` | 只做链接，产出原生可执行文件（默认 `<NativeImage / target>/<name>`） |
| `nativeDist` | 链接 → 打成 `<name>-<version>-<classifier>.tar.gz` 并生成 `.sha1`/`.sha256` → 装进本地 native 仓库 → 生成与上一版本的 bsdiff 补丁，返回 `NativeDist(archive, delta)` |

分发（与服务端仓库交互）：

| 键 | 说明 |
|----|------|
| `nativeDistUpload` | 从本地仓库取本版本整包并上传：归档 + `.sha1` + `.sha256`（按此顺序，先构件后校验） |
| `nativeDeltaUpload` | 从本地仓库取本版本最新的补丁并上传：补丁 + 补丁 `.sha1`（先构件后校验） |

两个上传任务都**不重新构建**，只读本地 native 仓库，几秒内完成；仓库里没有对应文件时只打印
`skip ... upload: cannot find ...` 告警并正常结束，因此可以放心地在发布脚本里单独调用。

设置：

| 键 | 默认值 | 说明 |
|----|--------|------|
| `nativeImageCommand` | 自动探测 | native-image 命令，按 `GRAAL_HOME` / `GRAALVM_HOME` / `JAVA_HOME` 找 `bin/native-image` |
| `nativeImageOptions` | `Nil` | 传给 native-image 的附加参数，如 `-Os`、`--no-fallback` |
| `nativeImageOutput` | `<NativeImage / target>/<name>` | 可执行文件输出路径 |
| `nativeDistClassifier` | 由 `os.name`/`os.arch` 推出 | 平台标签，如 `linux-amd64` / `darwin-arm64` |
| `nativeRepoHome` | `~/.m2` | 本地 maven 主目录；正式版装进 `<home>/repository`，快照版（版本号含 `SNAPSHOT`）装进 `<home>/snapshots` |
| `nativeRepoPath` | `<group 转路径>/<name>/<version>` | 本工程在 `/native` 里的相对路径，上传地址即 `<nativePublishUrl>/<nativeRepoPath>/<文件名>` |
| `nativeDistDeltaBase` | `None` | 增量基准版本，缺省取本地仓库中小于当前版本的最大正式版本（忽略 `-SNAPSHOT`） |
| `nativeDistDeltaHeap` | `"4g"` | 跑 bsdiff 的子 JVM 堆大小；与 sbt 自身的 `-Xmx` 无关 |
| `nativePublishUrl` | `"unknown-url"` | micdn 的 `/native` 基地址，如 `https://sas.openurp.net/native`；上传/下载同路径，不用模板 |

`NativeImage / target` 默认为 `<项目>/target/native-image`。

## 使用

都在终端项目（应用工程）里执行，`portal` 是项目名。

1）链接、打包、装进本地仓库并生成补丁（一句话完成；要发布上一版本时，先切到对应分支跑一次）：

```bash
sbt "portal/nativeDist"
# <项目>/target/native-image/             ← 可执行文件 + lib*.so
# <项目>/target/<name>-<version>-linux-amd64.tar.gz(.sha1/.sha256)
# ~/.m2/snapshots/<group>/<name>/<version>/<name>-<version>-linux-amd64.tar.gz(.sha1)
# ~/.m2/snapshots/<group>/<name>/<version>/<name>-<old>_<version>-<ts>-1-linux-amd64.tar.gz.diff(.sha1)
```

本地仓库里没有更低的正式版本、或该版本没有本平台发行包时，补丁这一步会打印
`skip native delta: no local distribution older than ...` 并跳过，构建仍然成功。

2）发布本版本整包（读本地仓库里刚 `nativeDist` 出来的那份，`nativePublishUrl` 建议写在
`build.sbt` 里）：

```scala
nativePublishUrl := "https://sas.openurp.net/native"
```

```bash
sbt "portal/nativeDistUpload"           # 不重新构建，秒级完成
# 上传 <name>-<version>-linux-amd64.tar.gz 及其 .sha1/.sha256
# 上传地址 = <nativePublishUrl>/<group 转路径>/<name>/<version>/<文件名>（全路径 URL，无 query string）
```

3）生成并上传增量补丁（diff 在子 JVM 里跑，堆看 `nativeDistDeltaHeap`，默认 `4g`）：

```bash
sbt "portal/nativeDeltaUpload"            # 取仓库里最新的补丁上传，秒级完成
# 上传 <...>.diff 及其 .diff.sha1（先构件、后校验文件）
```

补丁取版本目录里 `<name>-<基准版本>_<版本>[-<UTC 构建号>]-<classifier>.tar.gz.diff` 中构建号
最大的那个（正式包没有构建号，就一个）。若本版本还没生成过补丁，会打印告警并跳过。

4）客户端升级：保留上一版发行包 → 解出旧 tar → `Bsdiff.patch` 得到新 tar → 校验其 sha1 与
仓库中的整包 `.sha1` 是否一致 → 解压部署。也可以下载不带构建时间的补丁别名（见服务端一节）。

> 同一份源码两次构建出的归档 sha1 可能不同（native-image 链接不保证逐字节可复现），因此整包和
> 补丁最好配套上传，客户端以整包的 `.sha1` 为准校验重建结果。

## 行为

- 全部参数写入 `@native-image.args`：classpath 条目先 realpath 归一化（sbt 2 的 CAS
  让产物 jar 以符号链接形式出现，同一文件会以两条 URL 注册），写盘前后各校验一次无重复条目，
  重复即构建失败；`native-image` 以该参数文件为唯一输入，避免 `-cp` 过长；
- `nativeDist` 归档内容 = 可执行文件 + native-image 生成的 `lib*.so`。这些 `.so` 是运行期由
  JNI 动态加载的（AWT/freetype 等），不是链接进可执行文件的，必须随包；`native-image.args`
  等中间产物不入包；
- 归档固定使用 gzip：目标机上系统 `tar` 即可 `tar xzf` 解开（CentOS 8 的 tar 1.30 不支持
  `--zstd`，zstd 命令行也不是默认安装），Java 侧用内置 `GZIPInputStream`，不引入额外依赖；
- `nativeDist` 把发行包装进 `<仓库根>/<group 转路径>/<name>/<version>/`，包含归档与
  maven 风格的 `.sha1`。native 构件没有 pom，因此不产生 pom。

## 仓库布局

`nativeRepoHome`（默认 `~/.m2`）存放原生发行包、增量补丁及其校验和，按 maven 布局
`<group 转路径>/<name>/<version>/` 组织。落盘根目录与 war/jar 一致：正式版本进
`<home>/repository`，开发版（版本号含 `SNAPSHOT`，与 `SnapshotPlugin` 的快照仓库同址）
进 `<home>/snapshots`：

```
~/.m2/repository/org/beangle/beangle-ems-portal/4.20.13/
  beangle-ems-portal-4.20.13-linux-amd64.tar.gz
  beangle-ems-portal-4.20.13-linux-amd64.tar.gz.sha1
~/.m2/snapshots/org/beangle/beangle-ems-portal/4.20.14-SNAPSHOT/
  beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.gz        # 当前构建的发行包
  beangle-ems-portal-4.20.14-SNAPSHOT-linux-amd64.tar.gz.sha1
  beangle-ems-portal-4.20.13_4.20.14-SNAPSHOT-20260913.101500-1-linux-amd64.tar.gz.diff
  beangle-ems-portal-4.20.13_4.20.14-SNAPSHOT-20260913.101500-1-linux-amd64.tar.gz.diff.sha1
```

用哪个根由「构件所在的版本目录是否以 `-SNAPSHOT` 结尾」决定，下载侧同样如此，因此上传路径
与下载路径都仍是 `<group 转路径>/<name>/<version>/<文件名>`，与服务端 `/native` 的
`{path}` 模板一致，调用方无需区分两个根。

正式包与快照包同名规则一致（`<name>-<version>-<classifier>.tar.gz`），快照补丁在文件名里
带上构建时间（见下），便于同一 SNAPSHOT 版本多次构建并存。

## 增量补丁

`nativeDist` 里的补丁生成复用 `WarPlugin.warDiff` 同一套约定（`org.beangle.build.util.Bsdiff` +
`Dependency.m2Path`）：从本地 native 仓库取出上一版本的发行包，与当前发行包做 bsdiff，补丁落在
发行包所在版本目录（正式版 `<home>/repository`、快照版 `<home>/snapshots`）下，文件名为
`<name>-<旧版本>_<新版本>[-<构建号>]-<classifier>.tar.gz.diff`：正式包只有一份，不需要构建号；
快照包的同一个版本会反复构建，因此插入与 `SnapshotPlugin` 相同的 UTC 构建号
（`yyyyMMdd.HHmmss-1`），如
`beangle-ems-portal-4.20.13_4.20.14-SNAPSHOT-20260913.101500-1-linux-amd64.tar.gz.diff`。
补丁同时复制一份到发行目录并生成 `.sha1`。

- 基准版本：`nativeDistDeltaBase`，缺省扫描 `<home>/repository/<group 转路径>/<name>/`
  下的版本目录（只可能是正式版本），取其中「目录名是版本号、目录内确实存有本平台
  `<name>-<版本>-<classifier>.tar.gz`、且版本小于当前版本」的最大者。只认正式版本，
  `-SNAPSHOT` 目录一律忽略（快照包多半是本机
  反复构建的试验产物，未必发布过，拿它当基准对客户端没有意义），因此快照之间也不互相作基准。
  版本比较按 `.`/`-` 切段后逐段比，能转数字的按数字比，不按目录名字典序（否则 `4.20.9` 会排
  在 `4.20.13` 前面）；比当前版本高的目录、以及当前版本自己的目录都自然排除。不查 git tag：
  补丁基准只要求本地有同字节的那份包，与它是不是发布 tag 无关，
  这样在浅克隆、无 tag 或源码不在 git 仓库里时也能生成补丁；找不到基准就跳过，不算构建失败；
- 比对的是**解压后的 tar**。压缩流之间做 bsdiff 出来的补丁几乎等于整包
  （实测 41.5M / 41.8M，等于没有任何节省），所以两端都先 gunzip 再比对；
- bsdiff 会把两个文件整体读进内存并建后缀数组，150MB 级 tar 要几 GB 堆，所以默认把它丢到
  **子 JVM** 里跑，堆由 `nativeDistDeltaHeap` 指定（默认 `4g`）：不需要给 sbt 加堆，也不会
  把 sbt 自己撑爆。子 JVM 起不来（找不到 java、classpath 拼不出）时才回退到 sbt 进程内执行，
  这时才需要 `sbt -J-Xmx4G`，并会打印告警；
- 失败的 diff 只会留下空文件，`nativeDist` 遇到空补丁会删掉它并让任务失败，`nativeDeltaUpload`
  挑补丁时也会跳过 0 字节的文件，避免把坏补丁传上仓库；
- 实测（两个包都是 `-Os`）：4.20.13 → 4.20.14-SNAPSHOT 解压后各 143MB，子 JVM diff 117 秒
  得 14.7MB 补丁，打补丁 2.8 秒，重建结果与整包 sha1 逐字节一致；旧的非 `-Os` 基线对同一
  快照则要 45.5MB。

## 服务端仓库

上传与下载都由 [micdn](https://github.com/beangle/micdn) 的 `/native` 提供（war/jar 的
快照走同一个 micdn 实例的 `/maven`，见 [snapshot.md](snapshot.md)）。native 构件是 tar.gz
而非 war/jar，没有 MANIFEST.MF 可解析坐标，坐标完全由路径决定，因此**本地仓库布局
（`nativeRepoHome` 的 `repository`/`snapshots` 两个根）只是构建机的暂存**，上传路径仍统一为
`<group 转路径>/<name>/<version>/<文件名>`；服务端是**单根**（`${micdn.home}/native`），
正式版与开发版共用，靠版本目录名里的 `-SNAPSHOT` 区分。micdn 侧要**显式声明 `<native/>`**
（`<native base="…"/>` 可换仓库根）才挂载 `/native`——与 `<maven>` / `<npm>` 一样，元素就是端点开关：

- 上传：`PUT /native/{仓库内相对路径}`（全路径 URL，不用 query string），
  `Authorization: Bearer <token>`（`~/.sbt/snapshot_credentials` 里的 `token`，与
  [snapshot.md](snapshot.md) 同一份，对应 micdn 的 `<publish token="…"/>`）。
  先传构件、再传 `.sha1`；micdn 只做原子落盘，**不**校验构件与校验文件的先后或配对
  （sashub 时代的 `.pending` 暂存已不再需要）；
- 下载：`GET|HEAD /native/{仓库内相对路径}`；目录可列出，根为 `/native/`；
- 文件名里不含时间戳的 `-SNAPSHOT` 请求解析到同目录时间戳最新的同名构件（`HEAD` 用 `latest`
  响应头返回具体文件名并给出 `Last-Modified`，`GET` 重定向到该文件），客户端因此可以用别名
  探测/下载（如 `<name>-<旧版本>_<版本>-SNAPSHOT-<classifier>.tar.gz.diff`）。

## 示例

```scala
lazy val portal = (project in file("portal"))
  .enablePlugins(NativeImagePlugin)
  .settings(
    Compile / mainClass := Some("org.beangle.sas.engine.tomcat.Bootstrap"),
    nativePublishUrl := "https://sas.openurp.net/native",
    nativeImageOptions ++= Seq("-Os", "--no-fallback")
  )
```

```bash
sbt "portal/nativeDist"                                     # 链接 + 打包 + 装仓库 + 生成补丁
sbt "portal/nativeDistUpload"                               # 发布整包（读本地仓库）
sbt "portal/nativeDeltaUpload"                              # 发布增量补丁（读本地仓库）
```
