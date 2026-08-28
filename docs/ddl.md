# DdlPlugin DDL 报告与迁移脚本

自动启用（`trigger = allRequirements`）。

## 用途

围绕 `src/main/resources/db/postgresql/` 下的数据库元数据，提供两类能力：

- `ddlReport`：把 `report.xml` 渲染为 HTML 报告并在浏览器中打开；
- `ddlDiff`：对比两个版本的 `db-<version>.xml`，生成增量迁移 SQL。

## 任务

| 任务 | 说明 |
|------|------|
| `ddlReport` | 读取 `src/main/resources/db/postgresql/report.xml`，用 beangle-sqlplus 生成 HTML 报告到 `target/dbreport/` 并打开浏览器 |
| `ddlDiff oldVersion newVersion` | 对比 `db-<old>.xml` 与 `db-<new>.xml`，生成迁移 SQL 到 `target/db/postgresql/migrate/<old>-<new>.sql` |

## 行为

- `ddlReport` 依赖项目配置的 Maven 仓库解析 `org.beangle.sqlplus:beangle-sqlplus`，
  fork `org.beangle.sqlplus.report.Reporter`；
- `ddlDiff` 使用运行时 classpath 中的 `org.beangle.jdbc.meta.Diff`，对比
  `src/main/resources/db/postgresql/db-<oldVersion>.xml` 与
  `db-<newVersion>.xml`，产物为可直接执行的 SQL 脚本。

> 注意：`ddlDiff` 在参数不足时的提示文本仍沿用历史命名 `ormDdlDiff`，实际任务名
> 为 `ddlDiff`。

## 示例

```bash
sbt "Compile / ddlReport"
sbt "Compile / ddlDiff 4.19.0 4.20.0"
# 生成 target/db/postgresql/migrate/4.19.0-4.20.0.sql
```
