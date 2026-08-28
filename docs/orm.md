# OrmPlugin 建表 DDL 生成

自动启用（`trigger = allRequirements`）。

## 用途

从项目中的 ORM 映射（beangle-data 的 MappingModule）生成多数据库建表 DDL，
用于初始化数据库或对比表结构。

## 任务

| 任务 | 说明 |
|------|------|
| `ormDdl` | 为 PostgreSQL、MySQL、H2、Oracle、DB2、SQLServer 生成 DDL 到 `target/db/` |

## 行为

- 使用 `Runtime / fullClasspath`（保证 `org.beangle.data.orm.DdlGenerator` 与映射类
  都在 classpath 上）；
- fork `org.beangle.data.orm.DdlGenerator`，输出目录为 `crossTarget/db/`，locale 为
  `zh_CN`；
- 生成过程中若有警告，会输出 `warnings.txt` 并在构建日志中提示。

## 示例

```bash
sbt "Compile / ormDdl"
# 生成 target/scala-*/db/ 下的各数据库 DDL
```
