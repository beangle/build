# StylePlugin 格式检查与格式化

自动启用（`trigger = allRequirements`）。在编译前自动执行格式检查，并提供手工格式化任务。

## 用途

统一源码风格，避免在代码评审中纠结空白与许可证头问题：

1. 使用空格代替 tab 缩进；
2. 每行源代码不能以空格结尾；
3. 每个源文件需要以空行结尾；
4. 每个源文件头部需要声明许可证。

## 任务

| 任务 | 作用域 | 说明 |
|------|--------|------|
| `styleCheck` | Compile / Test | 检查空白（tab/CRLF、行尾空格）与许可证头；违规时抛错并列出文件，使编译失败 |
| `styleFormat` | Compile / Test | 手工格式化：tab→空格、去除行尾空格、补文件末尾空行、CRLF→LF、按需补/替换许可证头 |

检查范围是 `unmanagedSourceDirectories ++ unmanagedResourceDirectories`，只处理
`text/*`、`xml`、`js` 类型的文本文件。

## 自动执行

`styleCheck` 通过 [CompileHookPlugin](compile-hook.md) 注册到编译前钩子：

```scala
Compile / compilePreHooks += Def.task { (Compile / styleCheck).value }.taskValue
Test / compilePreHooks  += Def.task { (Test / styleCheck).value }.taskValue
```

因此每次 `compile` 前都会自动检查，无需手动执行。

## 许可证头

许可证头由以下 sbt 设置推导：

- `licenses`：仅当恰好声明一个许可证时生效，取其 SPDX id（如 `LGPL-3.0`）；
- `startYear`：起始年份；
- `organizationName`：版权所有者。

内置常见许可证模板（`src/main/resources/org/beangle/build/style/license/`）：
Apache-2.0、BSD-3-Clause、BSL-1.0、GPL-3.0、LGPL-3.0、MIT、MPL-2.0。
无法推导时使用占位符 `LICENSE NEEDED!!` 并在检查中报错。

## 设置

| 设置 | 默认 | 说明 |
|------|------|------|
| `headerEmptyLine` | `true`（全局） | 许可证头与正文之间是否需要空行分隔 |

## 打包

`packageBin` 的 Manifest 会自动附带 `Bundle-License` 属性（值取 `licenses` 的 SPDX id）；
若仓库中找不到对应许可证全文文本，会在构建日志中告警。

## 示例

```scala
ThisBuild / licenses := Seq(
  "LGPL-3.0" -> uri("https://www.gnu.org/licenses/lgpl-3.0.txt")
)
ThisBuild / startYear := Some(2005)
ThisBuild / organizationName := "The Beangle Software"

// 手工格式化
sbt "Compile / styleFormat"
```
