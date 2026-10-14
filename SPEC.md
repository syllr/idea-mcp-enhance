# SPEC.md — IDE MCP Enhance 规格

> **本文档定位**：项目级**行为规范**。定义"插件必须满足什么"，不描述"如何实现"（那在 `DESIGN.md`）。
>
> **三元组关系**：`AGENTS.md`（怎么开发）→ `SPEC.md`（必须满足什么，本文档）→ `DESIGN.md`（怎么实现）

---

## 0. 系统概览

本插件由两个**互不依赖**的能力组成：

| 能力                   | 入口                | 输出                                       |
| ---------------------- | ------------------- | ------------------------------------------ |
| 复制 prompt            | 编辑器右键菜单      | 系统剪贴板（`location:` 信封 + 内容）      |
| Run Lifecycle MCP 工具 | MCP（Open Code 等） | MCP `CallToolResult`（文本 + 结构化 JSON） |

**明确的非目标**（不要"顺手加回来"）：

- prompt 能力：不联网、不起进程、不读写配置文件与环境变量
- MCP 能力：**不自己实现 MCP server**。工具由 IDE 内置的 MCP Server 插件暴露出去，本插件只注册工具
- 不与特定 agent 耦合：产物是纯文本 / 标准 MCP，任何客户端都能消费

**数据流**：

```
prompt:  IDE 数据 → prompt/actions（取数）→ prompt/format（纯函数）→ 系统剪贴板

mcp:     JsonObject 入参 → ToolsProvider（编排）→ RunConfigAccess（平台）
         → RunLifecycleModels（纯函数出 JSON）→ McpToolCallResult
```

---

## 1. 契约 A：选区 → prompt（`Copy as Prompt`）

> 本节与上游 [`syllr/copy-as-prompt`](https://github.com/syllr/copy-as-prompt) 的 `SPEC.md §1` **逐字节一致**。改动必须同步两个仓库。

### 1.1 输出格式(HARD CONTRACT)

```text
location:<文件路径>:<行号或起止行号>
content:
```

<语言标识>
<选中代码>

```
␠␠
```

（最后一行是**两个空格**，见 §1.3）

| 项       | 规则                                                                                   |
| -------- | -------------------------------------------------------------------------------------- |
| 路径     | 优先**相对项目根**的路径（`src/Foo.kt`）；文件不在项目内时回退为绝对路径               |
| 行号     | 1-based，与编辑器状态栏一致。单行 → `10`；跨行 → `10-20`（首尾行均包含）               |
| 行号边界 | 选区恰好结束在某行行首时，末行取**最后一个被选中的字符**所在行（不把未选中的行算进去） |
| 语言标识 | 取自 PSI 语言 id 并**转小写**（`JAVA` → `java`）；取不到时输出裸围栏                   |
| 选中代码 | **原样**：不 trim、不重排缩进、不转义                                                  |
| 换行符   | `\n`                                                                                   |

**Spec**:

- 系统 MUST 由纯函数 `formatAsPrompt` 一次性生成该字符串，调用方不做后处理
- 系统 MUST 由 `PromptFormatTest` 覆盖：单行 / 跨行 / 大小写语言标识 / 缺失语言标识 / 代码含反引号 / 结尾形态

### 1.2 触发与启用条件

- **WHEN** 编辑器存在且选中非空白文本 → enabled，点击后写剪贴板并**清空选区**
- **WHEN** 无编辑器 / 无选区 / 选区全空白 → disabled；`actionPerformed` MUST 静默 return
- **WHEN** `project` / `Editor` / `PsiFile` / `virtualFile` 任一为 null → MUST 静默 return
- 系统 MUST NOT 在复制成功时弹通知或 dialog

### 1.3 结尾空行机制(HARD CONTRACT)

输出 MUST 以 `\n` + **两个空格** 结尾（`TRAILING_BLANK`）。

**原因**：目标输入框是 contenteditable 类实现，只渲染"有内容的行"。纯 `\n` 结尾不产生可见空行，用户粘贴后光标紧贴代码块末尾，继续输入会被吞进代码块。两个空格让末行"有内容"从而渲染出可见空行，视觉上不可见。

**MUST NOT** 省略、改成单个换行、或"清理尾随空格"。

---

## 2. 契约 B：诊断 → prompt（`Copy Diagnostics as Prompt`）

> 与上游 `copy-as-prompt` 的 `SPEC.md §2` 逐字节一致。

### 2.1 输出格式(HARD CONTRACT)

```text
location:<文件路径>
diagnostics:
- [ERROR] line 12: <消息>
  <出错行原文>
- [WARNING] line 14: <消息>
  <出错行原文>
␠␠
```

| 项       | 规则                                                                    |
| -------- | ----------------------------------------------------------------------- |
| 路径     | 同 §1.1                                                                 |
| 严重级别 | 只有 `ERROR` / `WARNING` 两档（映射规则见 §2.3）                        |
| 行号     | 1-based；诊断起点所在行                                                 |
| 消息     | **单行纯文本**：HTML 已剥离（`<br>` 转空格），换行折叠为空格，首尾 trim |
| 出错行   | 该行原文（trim 后），以**两个空格缩进**；该行无可见文本时整行省略       |
| 排序     | 行号升序 → 同行 ERROR 在前 → 消息字典序（保证输出可复现）               |
| 结尾     | 同 §1.3 的 `TRAILING_BLANK`                                             |
| 空诊断   | MUST NOT 产生输出（action 为 disabled）                                 |

### 2.2 采集范围与内容

- 范围是**整个文件**（不是选区）
- 采集来源 MUST 是 daemon 已生成的诊断，MUST NOT 触发一次额外的 inspection 全量分析
- 完全相同的诊断 MUST 去重

### 2.3 严重级别映射

| 平台 `HighlightSeverity`                                           | 输出           |
| ------------------------------------------------------------------ | -------------- |
| `>= ERROR`                                                         | `ERROR`        |
| `>= WARNING` 且 `< ERROR`                                          | `WARNING`      |
| `< WARNING`（`INFO` / `WEAK_WARNING` / `SYMBOL_TYPE_SEVERITY` 等） | 过滤掉，不输出 |

### 2.4 启用条件

- **WHEN** 编辑器存在且**文件内至少有一条 ERROR/WARNING** → enabled
- **WHEN** 无编辑器，或没有任何诊断 → disabled
- 判定 MUST 在命中第一条诊断时立即短路

---

## 3. 契约 C：Run Lifecycle MCP 工具

### 3.1 前置条件

- 插件 MUST 在 `com.intellij.mcpServer` **缺失**时仍能正常加载并提供 §1 / §2 的能力
- 注册 MCP 工具的代码 MUST 只出现在 `META-INF/mcpserver.xml`（由 `<depends optional="true" config-file="...">` 加载）
- 工具 MUST NOT 在用户没有显式启用 MCP Server 时以任何形式自行暴露

### 3.2 工具清单(HARD CONTRACT)

| 工具名                               | 只读 | 破坏性 | 幂等 |
| ------------------------------------ | ---- | ------ | ---- |
| `list_run_configurations_with_state` | ✓    | ✗      | ✓    |
| `get_run_configuration_status`       | ✓    | ✗      | ✓    |
| `stop_run_configuration`             | ✗    | ✓      | ✓    |
| `rerun_run_configuration`            | ✗    | ✓      | ✗    |

每个工具 MUST：

- 有非空 `description`，且**说明它为什么存在**（内置 MCP Server 缺哪块能力），而不只是罗列参数
- 有非空输入 schema，每个属性带 `type` 与 `description`
- 归属同一个 `McpToolCategory`
- 名字是合法 snake_case

### 3.3 定位配置名的规则(HARD CONTRACT)

- MUST 先**精确**匹配 `RunnerAndConfigurationSettings.name`
- 精确失败时 MUST 再按**忽略大小写**匹配
- 仍失败时 MUST 返回 `isError = true`，并在 `structuredContent.availableNames` 里给出全部非模板配置名（排序后）
- MUST NOT 静默创建一个同名配置

### 3.4 `list_run_configurations_with_state`

入参：

| 参数               | 类型    | 必填 | 默认   |
| ------------------ | ------- | ---- | ------ |
| `includeTemplates` | boolean | 否   | `true` |

行为：

- MUST 列出全部 Run Configuration；`includeTemplates = false` 时 MUST 排除模板
- MUST 附带实时状态：`running`、`runningInstanceCount`、`instances[]`
- 每个实例 MUST 含 `executionId` / `displayName` / `running` / `terminating` / `startNotified` / `pid` / `exitCode`
- MUST 按 `RunManager.allSettings` 的顺序输出（保证可复现）

输出 JSON：

```json
{
  "count": 6,
  "runningCount": 1,
  "configurations": [
    {
      "name": "console > dev",
      "type": "js.build_tools.npm",
      "isTemplate": false,
      "running": true,
      "runningInstanceCount": 2,
      "instances": [
        {
          "executionId": 1832,
          "displayName": "console > dev",
          "running": true,
          "terminating": false,
          "startNotified": true,
          "pid": 2100,
          "exitCode": null
        }
      ]
    }
  ]
}
```

### 3.5 `get_run_configuration_status`

- MUST 只读，MUST NOT 启动任何东西
- MUST 返回与 §3.4 相同的单个 `RunConfigurationState` JSON
- 文本摘要 MUST 在 `runningInstanceCount > 1` 时给出"被启动了不止一次"的告警——这是端口漂移的直接信号

### 3.6 `stop_run_configuration`

入参：

| 参数                | 类型    | 必填 | 默认   | 说明                                        |
| ------------------- | ------- | ---- | ------ | ------------------------------------------- |
| `configurationName` | string  | 是   | —      | 要停的配置名                                |
| `waitTimeoutMs`     | integer | 否   | `5000` | 等待进程真正退出的上限；≤0 视为非法，取默认 |
| `force`             | boolean | 否   | `true` | 超时后是否强杀                              |

行为：

- MUST 停掉该配置名下**所有**未被终止的实例（不是只停最新的一个）
- MUST 通过 IDE 自己的 `ProcessHandler` 停止，MUST NOT 用外部 `pkill` / 按端口杀
- MUST 在 `waitTimeoutMs` 内等待实例真正退出
- `force = true` 且超时后 MUST 对仍在终止中的 `KillableProcess` 调用 `killProcess()`
- `configurationName` 缺失 → `isError = true`，`structuredContent.argument = "configurationName"`
- 该配置没在跑 → `isError = false`（这是正常状态，不是错误），`matchedInstances = 0`

输出 JSON：

```json
{
  "configurationName": "console > dev",
  "matchedInstances": 2,
  "requestedStops": 2,
  "forcedKills": 0,
  "timedOut": false,
  "allTerminated": true,
  "instances": [ ... ],
  "warnings": []
}
```

### 3.7 `rerun_run_configuration`

入参：

| 参数                | 类型    | 必填 | 默认   |
| ------------------- | ------- | ---- | ------ |
| `configurationName` | string  | 是   | —      |
| `stopTimeoutMs`     | integer | 否   | `5000` |

行为 —— MUST 等价于 IDE 的 Rerun 按钮：

| 起始状态           | MUST 做什么                                        | `mode`      |
| ------------------ | -------------------------------------------------- | ----------- |
| 至少一个实例在跑   | 先停掉全部活着的实例（等 `stopTimeoutMs`），再启动 | `RESTARTED` |
| 没有实例在跑       | 直接启动                                           | `STARTED`   |
| IDE 造不出执行环境 | 什么都不做，`warnings[]` 说明原因                  | `NO_OP`     |

- MUST 通过 `ExecutionManager.restartRunProfile(ExecutionEnvironment)` 启动，与界面按钮同一条代码路径
- MUST NOT 改写项目的启动命令，MUST NOT 按命令行模式匹配，MUST NOT 依赖固定监听端口
- 旧实例超时未退出时 MUST 仍然启动新实例，并在 `warnings[]` 里说明
- 输出 JSON：

```json
{
  "configurationName": "console > dev",
  "mode": "RESTARTED",
  "stoppedInstances": 1,
  "runningAfter": 1,
  "instances": [ ... ],
  "warnings": []
}
```

### 3.8 返回值通用约定

- 成功 MUST 用 `McpToolCallResult.text(摘要文本, 结构化JSON)`
- 失败 MUST 用 `McpToolCallResult.error(说明文本, 结构化JSON)`
- 文本摘要 MUST 是**一句人话**（agent 会同时看到文本与 JSON），MUST NOT 只是把 JSON 复读一遍
- **null 字段 MUST 保留为 JSON `null`，MUST NOT 省略该键**——agent 要能区分"键不存在"（工具出错）与"值为 null"（进程还没起来）
- 数组 MUST 输出 `[]` 而非省略

### 3.9 异常处理(HARD CONTRACT)

- 工具执行期间抛出的任何异常 MUST 被捕获并转成 `McpToolCallResult.error("Tool <name> failed: <原因>")`
- MUST NOT 把异常抛给 MCP Server（那样 agent 只能看到不可读的失败）

---

## 4. 安全与代码规范(HARD RULES)

### 4.1 包名与标识

- **MUST** 使用 `com.shenyuanlaolarou.ideamcpenhance` 包名
- **MUST NOT** 使用其他包名

### 4.2 分层

- **MUST** 把 prompt 的格式化逻辑放在 `prompt/format/` 且实现为**纯函数**
- **MUST** 把 MCP 的 JSON 构造放在 `RunLifecycleModels.kt` 且实现为**纯函数**
- **MUST NOT** 在 action / 工具类里手写 JSON 或字符串格式化

### 4.3 平台 API 使用

- **MUST NOT** 使用 `@TestOnly` / `@Internal` 标注的平台 API
- **MUST NOT** 使用 `com.intellij.execution.impl.*`（含 `ExecutionManagerImpl`）
- 读取 daemon 诊断 MUST 走 document 级 markup model

### 4.4 自动化约束

| 规则                                  | 说明                                                             |
| ------------------------------------- | ---------------------------------------------------------------- |
| AI 禁止自动 `git commit` / `git push` | 必须用户显式授权（`/git-commit` 或 prompt 中 `commit` / `push`） |
| AI 禁止自动 `publishPlugin`           | 必须用户显式授权                                                 |
| AI 禁止修改 `local.properties` 凭证   | 发布令牌 / 私钥等**绝对**不能进 git                              |

### 4.5 Type 安全与静态状态

- **MUST NOT** 使用 `as Any` / Kotlin 等价的 type erase 绕过 / 滥用 `!!`
- **MUST** 用 `thisLogger().info/warn/error()` 记录日志
- **MUST NOT** 在 `object` 里挂 `var` 或非常量的 `MutableMap`

### 4.6 依赖最小化

- **MUST NOT** 引入第三方运行时依赖
- 新增依赖 MUST 在 §5.2 登记并说明不可替代的理由

### 4.7 隐私

插件**不联网**、不写用户文件、不采集遥测。唯一的外部系统交互面是系统剪贴板（prompt）与用户显式触发的进程启停（MCP）。

写入剪贴板的内容可能包含用户源码，**MUST NOT** 把剪贴板内容写入日志。

---

## 5. 部署与环境约束

### 5.1 分发

- 产物: `./gradlew buildPlugin` → `build/distributions/idea-mcp-enhance-<版本>.zip`
- 调试运行: `./gradlew runIde`
- **首次发布 MUST 网页手动上传**（Marketplace 对未创建过的插件 id 不接受 Gradle 上传）

### 5.2 依赖

| 依赖                             | 版本              | 说明                                                 |
| -------------------------------- | ----------------- | ---------------------------------------------------- |
| Kotlin                           | 2.3.20            | —                                                    |
| Gradle                           | 9.3.1             | —                                                    |
| JDK                              | 21                | 编译/运行（`jvmToolchain(21)`）                      |
| IntelliJ Platform                | 2026.2.3          | `pluginSinceBuild=262`，`pluginUntilBuild` 留空      |
| IntelliJ Platform Gradle Plugin  | **2.19.0**        | 2.14.0 解析不了 2026.2 的 module 描述符              |
| JUnit                            | 4.13.2            | 测试                                                 |
| opentest4j                       | 1.3.0             | 测试断言                                             |
| IntelliJ Platform TestFramework  | 2026.2.3          | 平台集成测试（当前不可用，见 AGENTS.md「已知阻塞」） |
| `com.intellij.mcpServer`（内置） | 262.x             | **可选依赖**：MCP 工具的唯一来源                     |
| `com.intellij.java`（内置）      | —                 | **仅测试**；不进 `plugin.xml`                        |
| Qodana（linter / Gradle plugin） | 2024.3 / 2025.3.1 | 静态分析                                             |
| Kover                            | 0.9.5             | 覆盖率                                               |

**MUST NOT** 新增上表之外的依赖而不先更新本节。

### 5.3 平台与系统约束

| 项       | 约束                                                                                                 |
| -------- | ---------------------------------------------------------------------------------------------------- |
| 操作系统 | 无平台限制（纯平台 API + AWT）                                                                       |
| 目标 IDE | **2026.2（build 262）及以上**；`platformBundledPlugins` 里的 `com.intellij.mcpServer` 决定了这一下限 |
| 外部命令 | 无                                                                                                   |
| 用户配置 | 无（不读写任何配置文件）                                                                             |

---

## 6. 日志约定

- **MUST** 用 `thisLogger().info/warn/error()`；前缀格式 `[<类名>] <消息>`
- **MUST NOT** 输出凭证、剪贴板内容、源码内容、进程输出全文
- 当前实现的成功路径无日志（复制即反馈；MCP 工具的成功反馈是返回值）

---

## 版本与变更

见 [`CHANGELOG.md`](./CHANGELOG.md)。当前版本 `0.1.0`。
