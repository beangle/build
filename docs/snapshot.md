# SnapshotPlugin 快照版构建与上传

需要显式启用：`.enablePlugins(SnapshotPlugin)`（`trigger = noTrigger`）。

## 用途

把 SNAPSHOT 版本的 war/jar 打包为带时间戳的文件名，并以 HTTP PUT 上传到
[micdn](https://github.com/beangle/micdn) 的 `/maven` 发布端点（需在该实例的
`micdn.xml` 里声明 `<auth publish-key="…"/>`），便于开发期部署联调。

micdn 只按仓库相对路径原样落盘：`-SNAPSHOT` 别名 302 到最新时间戳文件、artifact 级
`maven-metadata.xml` 合并本地快照版本，都由它自己的读路径生成，因此这里不需要额外上传
`maven-metadata.xml`。

## 任务与设置

| 键 | 类型 | 说明 |
|----|------|------|
| `snapshotBuild` | 任务 | 生成带时间戳的快照包及其 `.sha1` 校验文件（仅当版本号含 `SNAPSHOT` 且产物为 war/jar） |
| `snapshotUpload` | 任务 | 将 `snapshotBuild` 的产物及 `.sha1` 校验文件 PUT 到 micdn 的 `/maven` |
| `snapshotRepoUrl` | 设置，默认 `"unknown-url"` | micdn 的 `/maven` 入口地址，如 `http://127.0.0.1:8080/maven` |
| `snapshotCredentials` | 设置，默认 `~/.sbt/snapshot_credentials` | 上传令牌的属性文件 |

## 行为

- `snapshotBuild`：把 `Compile / package` 的产物复制为
  `<artifactId>-<version 去 SNAPSHOT>-yyyyMMdd.HHmmss-1.<war|jar>`，存放于
  `snapshotBuild / target` 目录，并在同目录生成对应的
  `<文件名>.sha1` 校验文件（内容为产物的 SHA-1 十六进制摘要）；时间戳采用
  UTC 时间（`yyyyMMdd.HHmmss`）；
- `snapshotUpload`：按 maven 仓库布局拼上传路径
  `{organization 的点换成斜杠}/{artifactId}/{version}/{文件名}`（`version` 含 `-SNAPSHOT`），
  以 `PUT`（`Content-Type: application/octet-stream`）依次上传产物及其 `<文件名>.sha1` 校验文件：
  - 凭据只有 `token` 一种形式，走 `Authorization: Bearer <token>`；
  - 凭据文件缺失或没有 `token` 时报错并中止（不发任何请求）；
  - 2xx 视为成功（micdn 返回 `201 Created`）；
- 版本号不含 `SNAPSHOT` 或产物不是 war/jar 时，两个任务都会跳过并告警。

## 凭据文件

`~/.sbt/snapshot_credentials`（Java properties 格式）：

```properties
token=6bf65bdce91a9f8ba68aca25175076fa
```

只认 `token`；不再支持 `user` / `password`（native 的 `/native` 上传同样用这个 token，
见 [native.md](native.md)）。

## 示例

```scala
.settings(
  snapshotRepoUrl := "http://127.0.0.1:8080/maven"
)
```

```bash
sbt snapshotBuild snapshotUpload
```
