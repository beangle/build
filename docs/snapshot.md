# SnapshotPlugin 快照版构建与上传

需要显式启用：`.enablePlugins(SnapshotPlugin)`（`trigger = noTrigger`）。

## 用途

把 SNAPSHOT 版本的 war/jar 打包为带时间戳的文件名，并上传到指定的快照仓库，
便于开发期部署联调。

## 任务与设置

| 键 | 类型 | 说明 |
|----|------|------|
| `snapshotBuild` | 任务 | 生成带时间戳的快照包及其 `.sha1` 校验文件（仅当版本号含 `SNAPSHOT` 且产物为 war/jar） |
| `snapshotUpload` | 任务 | 将 `snapshotBuild` 的产物及 `.sha1` 校验文件 POST 上传到快照仓库 |
| `snapshotRepoUrl` | 设置，默认 `"unknown-url"` | 上传地址，可用 `{fileName}` 占位符 |
| `snapshotCredentials` | 设置，默认 `~/.sbt/snapshot_credentials` | 上传认证的属性文件 |

## 行为

- `snapshotBuild`：把 `Compile / package` 的产物复制为
  `<artifactId>-<version 去 SNAPSHOT>-yyyyMMdd.HHmmss-1.<war|jar>`，存放于
  `snapshotBuild / target` 目录，并在同目录生成对应的
  `<文件名>.sha1` 校验文件（内容为产物的 SHA-1 十六进制摘要）；时间戳采用
  UTC 时间（`yyyyMMdd.HHmmss`）；
- `snapshotUpload`：以 `POST`（`Content-Type: application/zip`）依次上传产物及其
  `<文件名>.sha1` 校验文件；若凭据文件中含 `user` / `password`，则附加 Basic Auth；
  `{fileName}` 会被替换为实际文件名；
- 版本号不含 `SNAPSHOT` 或产物不是 war/jar 时，两个任务都会跳过并告警。

## 凭据文件

`~/.sbt/snapshot_credentials`（Java properties 格式）：

```properties
user=deploy
password=secret
```

## 示例

```scala
.settings(
  snapshotRepoUrl := "http://repo.example.com/upload/{fileName}"
)
```

```bash
sbt snapshotBuild snapshotUpload
```
