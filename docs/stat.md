# StatPlugin 代码行数统计

自动启用（`trigger = allRequirements`）。

## 用途

统计项目中各类文件的非空代码行数，按文件扩展名汇总，帮助了解项目规模。

## 任务

| 任务 | 作用域 | 说明 |
|------|--------|------|
| `statSloc` | Compile / Test | 统计项目目录下各扩展名文件的非空行数并输出 |

> 注意：早期 README 中写作 `statLoc`，实际任务名为 `statSloc`。

## 行为

- 遍历 `baseDirectory`（即项目根目录），排除 `target` 目录；
- 跳过隐藏目录（以 `.` 开头）；
- 按文件扩展名统计非空行（空白行不计入）；
- 输出总行数以及按行数降序的各扩展名明细。

## 示例

```bash
sbt "Compile / statSloc"

# myproject has 12345 lines.
# scala      8000
# xml        2345
# js         2000
```
