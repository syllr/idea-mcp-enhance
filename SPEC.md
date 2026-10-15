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

| 工具名                        | 只读 | 破坏性 | 幂等 |
| ----------------------------- | ---- | ------ | ---- |
| `list_running_configurations` | ✓    | ✗      | ✓    |
| `stop_run_configuration`      | ✗    | ✓      | ✓    |
| `create_shell_run_configuration` | ✗ | ✗   | ✗    |
| `rerun_run_configuration`     | ✗    | ✓      | ✗    |

每个工具 MUST：

- 有非空 `description`，且**说明它为什么存在**（内置 MCP Server 缺哪块能力），而不只是罗列参数
- 有非空输入 schema，每个属性带 `type` 与 `description`
- 归属同一个 `McpToolCategory`
- 名字是合法 snake_case

> 只有三个工具的取舍：内置的 `get_run_configurations` 已经回答"有哪些配置"（静态），
> 本插件负责"现在到底什么在跑"（动态）与"停 / 重启"。曾经有过第四个工具
> `get_run_configuration_status`（按名查单个配置的状态），经实测判定冗余并**移除**：
> 它唯一独占的能力是"省一次全量拉取"，而"名字纠错"由 §3.5 / §3.6 的按名工具提供
> （同一套解析：精确 → 忽略大小写 → 列候选名），"看实时状态"由 `list_running_configurations`
> 提供（项目里并发的作业本来就不多，全量拉一次很便宜）。工具少一个，agent 少一次选择。

### 3.3 定位配置名的规则(HARD CONTRACT)

- MUST 先**精确**匹配 `RunnerAndConfigurationSettings.name`
- 精确失败时 MUST 再按**忽略大小写**匹配
- 仍失败时 MUST 返回 `isError = true`，并在 `structuredContent.availableNames` 里给出全部非模板配置名（排序后）
- MUST NOT 静默创建一个同名配置

### 3.4 `list_running_configurations`

入参：

| 参数          | 类型   | 必填 | 默认 |
| ------------- | ------ | ---- | ---- |
| `projectPath` | string | 否   | —    |

**只要 `projectPath`**，没有模板开关、也没有"是否只看在跑的"开关。理由：

- 模板不是实例、**永远不会在跑**，所以"只返回在跑的"天然把它排除，再加开关是多余的
- 工具名就叫 running，那就不该存在"返回全部配置"这种模式；想知道"有哪些配置"用内置的 `get_run_configurations`

行为：

- MUST 只返回**至少有一个活实例**的配置；没有在跑的配置 MUST 完全不出现
- 每个配置的 `instances[]` MUST 只含**活实例**（已结束的 Run 内容——Run 工具窗口留下的标签页——MUST 被剔除）
- 于是有三个恒等式：`runningInstanceCount` == `instances` 的长度、`running` 恒为 true、`exitCode` 恒为 null
- 每个实例 MUST 含 `executionId` / `displayName` / `running` / `terminating` / `startNotified` / `pid` / `exitCode`
- MUST 按 `RunManager.allSettings` 的顺序输出（保证可复现）；筛选 MUST 是纯过滤（`onlyRunningInstances()`），MUST NOT 重排
- 文本摘要 MUST 在"什么都没在跑"时直说（例如 `Nothing is running in this project.`）

> 与 `stop` / `rerun` 的区别是刻意的：那两个工具报告"它实际动过哪些实例"，所以保留已结束的记录
> （退出码正是"停干净了没有"的证据）；list 回答的是"现在什么在跑"，所以整份返回里不含任何不在跑的东西。
> 三个工具复用同一个元素结构，所以 list 里 `isTemplate` / `running` 会是常量——不影响校验。

输出 JSON：

```json
{
  "runningCount": 1,
  "configurations": [
    {
      "name": "console > dev",
      "type": "js.build_tools.npm",
      "isTemplate": false,
      "running": true,
      "runningInstanceCount": 1,
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

### 3.5 `stop_run_configuration`

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
- **"启动在飞" MUST 特殊处理(HARD CONTRACT)**：该配置有"已提交但还没产出进程"的启动时
  （`rerun` 的异步模式刚返回、或别的工具/人手刚发起），MUST 先**有界等待**
  （`PENDING_START_ATTACH_TIMEOUT_MS`，2000ms）让它挂上进程再照常停；等不到 MUST 如实说明
  "启动仍在飞、什么都没停、稍后可能起来，请再调一次或先查状态"，**MUST NOT 报
  `is not running; nothing to stop`** —— 那是假话：进程随后就会起来，而调用方以为已经停掉了
  （实测：stop 返回成功 3 秒后进程在跑）
- **可重入(HARD CONTRACT)** —— 重复 stop MUST 幂等成功，MUST NOT 因为"它已经停了"报错；
  并发 stop MUST 也各自安全（后到的一个通常看到"没在跑"，同样返回成功）。
  理由：agent 在"确保它停掉"的意图下重复调用是完全正常的，不该让它去分辨"是失败还是本来就没跑"

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

### 3.7 `create_shell_run_configuration`

只建 **Shell Script** 类型（`ShConfigurationType`）——没有 `type` 参数。
用途：Shell 配置自带项目的执行环境（工作目录、环境变量、解释器），agent 需要"用项目环境跑命令"时，
先用这个工具建一个，再用 `rerun_run_configuration` 启动它。

入参：

| 参数                 | 类型    | 必填 | 默认    | 说明                                                                                |
| -------------------- | ------- | ---- | ------- | ----------------------------------------------------------------------------------- |
| `configurationName`  | string  | 是   | —       | 新配置名，必须与现有配置不重名                                                      |
| `scriptPath`         | string  | 否   | —       | 跑一个脚本文件（相对项目根或绝对路径）；与 `scriptText` / `cloneFrom` 三选一        |
| `scriptText`         | string  | 否   | —       | 直接跑一段内联脚本；与 `scriptPath` / `cloneFrom` 三选一                            |
| `cloneFrom`          | string  | 否   | —       | 克隆一个已有的 **Shell** 配置（脚本 + 环境 + before-launch 一起拷）；与前两者三选一 |
| `scriptOptions`      | string  | 否   | —       | 传给脚本的参数                                                                      |
| `workingDirectory`   | string  | 否   | —       | 脚本的工作目录；不给就是模板默认值                                                  |
| `interpreterPath`    | string  | 否   | —       | 解释器（如 `/bin/zsh`）；不给就是模板默认值                                         |
| `interpreterOptions` | string  | 否   | —       | 传给解释器的参数                                                                    |
| `envs`               | object  | 否   | —       | 额外的环境变量（string 到 string）；非字符串的值跳过                                |
| `passParentEnvs`     | boolean | 否   | `true`  | 是否继承 IDE 的环境                                                                 |
| `executeInTerminal`  | boolean | 否   | `false` | 在终端里跑，而不是 Run 工具窗口                                                     |
| `filePath`           | string  | 否   | —       | 落点（相对项目根或绝对路径），默认 `.run/<配置名>.run.xml`                          |
| `projectPath`        | string  | 否   | —       | 见「项目解析」                                                                      |

行为：

- MUST 只创建**项目级**配置：调 `RunnerAndConfigurationSettings.storeInArbitraryFileInProject(...)`
  **直接写 `.run.xml` 文件**（纯字符串生成，不调平台创建 API）。
  **MUST NOT 提供 `shared` / 本地工作区开关**
- 默认落点 MUST 与 IDE 一致：`<项目根>/.run/<配置名>.run.xml`（配置叫什么，文件就叫什么）；
  `filePath` 可以覆盖它——**落点是调用方的选择，工具不自作主张**
- 文件形状 MUST 与 IDE 自己写的字节一致：8 个业务 option（`SCRIPT_*` / `INTERPRETER_*` /
  `EXECUTE_*`）+ `<envs>` + `<method>`，`INDEPENDENT_*` 三行带 `true`；属性值 MUST 转义
  （`& < > "`，换行写成 `&#10;`——XML 属性里的原始换行会被规整成空格）；
  相对路径 MUST 转成 `$PROJECT_DIR$` 宏；没给的字段写空值（与 IDE 存"空字段"的方式一致）
- `cloneFrom` MUST 用 `RunConfiguration.clone()`（选项 + before-launch 一起拷），
  读源文件改名（只换 `<configuration>` 标签里的 `name`，别人的 `run_configuration_name` 引用不动）；
  且源 MUST 本身就是 Shell 类型，否则拒绝；目标是**模板**时 MUST 拒绝（模板不是实例）
- 结果 MUST 回传 `canRun`（平台自己的 `checkSettings`）与 `canRunBlockedReason`
  （如脚本文件不存在）；`createdFrom` MUST 是 `script:<路径>` / `script-text` / `clone:<源名>` 之一；
  文件刚写完、IDE 还没加载时 `canRun` 为 false（原因是"尚未加载"，不是"配置有问题"），MUST 如实报告
- 重名 MUST 拒绝并回 `RunManager.suggestUniqueName` 给出的建议名；
  `cloneFrom` 不存在 MUST 走既有的候选名机制；
  `scriptPath` / `scriptText` / `cloneFrom` 给零个或多个 MUST 拒绝；
  目标文件已存在 MUST 拒绝（没有 delete 工具，不悄悄覆盖）
- 写完 MUST **刷新 VFS**（`LocalFileSystem.refreshAndFindFileByNioFile`）再等 IDE 文件监听注册
  （有界等待）：监听器只处理 `LocalFileSystem` 的创建事件，纯 nio 写入的文件 VFS 注意不到，
  就既不触发监听器也不进配置列表（实测：文件落盘但等满 3 秒未注册）
- 注册上就用 `checkSettings` 问"能不能跑"；等不到 MUST 报告"文件已写、尚未加载"，
  MUST NOT 谎报"创建失败"或"能跑"

### 3.6 `rerun_run_configuration`

入参：

| 参数                | 类型    | 必填 | 默认    |
| ------------------- | ------- | ---- | ------- |
| `configurationName` | string  | 是   | —       |
| `stopTimeoutMs`     | integer | 否   | `5000`  |
| `startTimeoutMs`    | integer | 否   | `10000` |
| `waitForStart`      | boolean | 否   | `true`  |

行为 —— MUST 与 IDE 的 Rerun 按钮有同样的可观察效果：

| 起始状态           | MUST 做什么                                        | `mode`      |
| ------------------ | -------------------------------------------------- | ----------- |
| 至少一个实例在跑   | 先停掉全部活着的实例（等 `stopTimeoutMs`），再启动 | `RESTARTED` |
| 没有实例在跑       | 直接启动                                           | `STARTED`   |
| IDE 造不出执行环境 | 什么都不做，`warnings[]` 说明原因                  | `NO_OP`     |

- **`waitForStart = true`（默认）时 MUST 同步等到新进程真的被创建**（并采集一段固定窗口的启动输出）之后才返回；
  MUST NOT 发出启动请求后立刻做快照 —— 那样 `runningAfter` 必然读到 0，调用方会把"启动成功"误读成"启动失败"
- **`waitForStart = false` 时 MUST 只提交、立刻返回**，且结果必须**如实表达"不知道"**：
  `started = null`、`runningAfter = null`、`startFailure = null`、`sessionId = null`、`output = ""`，
  `transition[]` 以 `START_SUBMITTED` 收尾，`warnings[]` 带固定文案（说明结果未知、要用
  `list_running_configurations` 轮询）。MUST NOT 用 `started = false` 冒充"不知道"——那会让 agent 去重试，
  反而可能起出第二个实例。这个模式存在的唯一理由：before-launch（构建 / 另一个配置）比服务器会话耐心还长时，
  任何等待都会把结果丢成空 404，只有"提交 + 自己轮询"可行。参数名 MUST 对齐原生 `execute_run_configuration` 的 `waitForExit`
- **启动前的三道判断的"时机" MUST 是：停旧实例之前先问一次，真正提交之前再问一次**（HARD CONTRACT）：
  ① 现在是否有模态对话框在等用户（扫 `Window.getWindows()` 里可见且带模态语义的 `Dialog`；
  **MUST NOT 用 `DialogWrapper.findInstanceFromFocus()`**——它读 `getFocusOwner()`，IDE 窗口没被聚焦时是 null，
  实测判断会落空）② 这个配置现在能不能跑（`checkSettings`）③ 平台是否已经在启动它（`isStarting`）。
  **先问再停**是为了不让用户白掉一次服务：否则会"先把在跑的应用杀掉，再告诉用户需要你去 IDE 处理"（实测过这个坏顺序）。
  **提交前再问一次**是因为状态会在"停止 + 构建执行环境"期间变化（用户中途打开对话框、别人抢先启动）。
  第 ① 层是**通用**的 EDT 卡点检测：`checkSettings` 只拦得住"平台因为**这个配置**而不能跑"，拦不住"**别的**对话框占着 EDT"
- **不能跑的时候 MUST 结构化拒绝，MUST NOT 让它走到"弹对话框"那条路**：平台在
  `ExecutionManagerImpl.executeConfiguration` 里先问 `RunManagerImpl.canRunConfiguration`（实现是
  `settings.checkSettings(executor)`），为 false 时**弹 `RunDialog.editConfiguration` 模态对话框**。
  agent / 无头场景里没人点它 ⇒ EDT 被永久占住 ⇒ 我们那次 `withContext(Dispatchers.EDT)` 提交再也回不来，
  请求一直悬着、会话被移除、调用方拿到空 404（比"启动失败"还糟）。因此 MUST 用**同一个公开方法**
  `RunnerAndConfigurationSettings.checkSettings(executor)` 提前问一次：`IndexNotReadyException`（IDE 在建索引，
  刚打开大项目时很常见）与 `RuntimeConfigurationError`（配置本身有致命问题）都要转成立刻、可读的结构化错误。
  `checkSettings` 不覆盖 before-launch（`checkSettingsBeforeRun()` 默认空实现），这**不是缺陷**：before-launch 配错了
  是使用姿势问题，执行时就会暴露。另外 **MUST NOT 为"提交到 EDT"这一步新增超时**：它与"等进程创建"共用同一个
  `startTimeoutMs` 预算（默认 10000）。但 MUST 保留这个上限——完全不留的话工具永远返回不了任何东西，
  AI 拿到空 404，**也就没法把"请用户去 IDE 处理"转告出去**，而那正是这类卡点唯一的出路。
  超时文案 MUST 区分"卡在哪一步"：卡在提交 ⇒ 多半是平台在弹模态对话框等用户交互，
  MUST 要求 AI 转告用户去 IDE 里处理（并提醒排队中的提交可能稍后才真的发生）；卡在等进程 ⇒ 走 before-launch 那套文案
- MUST NOT 用"拿到第一段输出就返回"来缩短等待：第一段通常是回显的启动命令行，MUST 采满窗口再返回
- MUST 用 `ProgramRunner.Callback` 接住 `processStarted` / `processNotStarted`
  （经 `ProgramRunnerUtil.executeConfigurationAsync`），与内置 `execute_run_configuration` 同一条路
- MUST 在 `processStarted` 里给 `ProcessHandler` 挂输出监听并 `startNotify()`
- `output` MUST 是新进程输出的内联预览，上限 10000 字符，超出时补 `<truncated>`
- `fullOutputPath` MUST 指向完整日志文件，进程运行期间持续增长
- `sessionId` 语义 MUST 与内置工具一致：单活实例用配置名，多活实例用 `<名字>#<executionId>`
- `transition[]` MUST 按时间顺序给出状态节点（`STOPPED` / `STARTED` / `START_SUBMITTED` / `START_FAILED`）并带每步耗时；
  停掉的旧实例的退出码 MUST 出现在第一个节点的 `detail` 里
- `STARTED` 节点的 `elapsedMs` MUST 是"到进程真的被创建"的时间，MUST NOT 把返回前采集日志的固定窗口算进去
  （否则摘要会写成"启动用了 2 秒"，而实际是等日志等了 2 秒）
- 判断"新进程到底起没起" MUST 看 `started`，MUST NOT 只看 `mode`；`started` 是可空布尔，
  `null` = "没等，不知道"（与仓库既有约定一致："值为 null = 进程还没起来"）
- MUST NOT 改写项目的启动命令，MUST NOT 按命令行模式匹配，MUST NOT 依赖固定监听端口
- **中间态 MUST 拒绝**（与 GUI 把 Rerun 按钮灰掉的状态同源：`FakeRerunAction.isEnabled` 判
  `isStarting()` / `isProcessTerminating()`），且必须覆盖**两档**：
  1. 快照里能看到的中间态实例：`terminating`，或已创建进程但还没 `startNotified`
  2. **Run 内容已创建、但进程还没挂上**（`processHandler == null`）——这一档在快照里是被丢掉的
     （`toInstanceState` 对无 handler 的条目返回 null），而人手按下 Run 之后的那一小段恰好落在这里

  拒绝 MUST 用 `isError = true` + 自洽文案（写明配置名与是启动中还是停止中，并建议稍后重试）。
  这一层读的是平台里的**真实状态**，因此 MUST 同时覆盖"用户在 IDE 里手动点 Run/Rerun"与
  "内置 `execute_run_configuration`"这两种非本插件的发起来源

- **发起启动前 MUST 先过平台自己的那道门**：`ProgramRunnerUtil.executeConfigurationAsync` 的源码是
  `if (!manager.isStarting(environment)) { …executeConfiguration… }` —— 即"同配置 + 同 executor + 同 runner
  正在启动"时，这次提交会被**静默丢弃**（连回调都不装），既不回成功也不回失败。
  因此 `rerun` MUST 在真正发起之前用**同一个条件**（公开 API `ExecutionManager.isStarting(environment)`）
  问一次，命中就立刻返回 `isError = true` + 自洽文案。
  这样 MUST NOT 依赖本插件自建的"在飞"内存状态——平台状态对"谁发起的"一视同仁，手动点击起的运行同样算数
- 碰撞窗口 = `inProgress` 的存活期 = **整个启动过程**：`executeConfiguration` 一进来就加 `inProgress`，
  直到进程创建、`startNotify` 之后才减（源码里是 `inProgress.update { it - inProgressEntry }` 紧跟
  `processHandler.startNotify()`）。而 before-launch 任务是在这中间跑的 ⇒ **构建期间 `isStarting` 就是 true**
  （实测：给配置挂一个永不退出的 before-launch 后，第二次 rerun 立刻拿到"another start is already in progress"，
  0 秒返回）。所以这个门能覆盖"构建中又发一次 rerun"这种长窗口场景，命中时给的是 0 秒的精确错误，
  而不是让调用方干等到自己的超时（默认值下"慢停 + 慢启"的总时长还会超过会话耐心，结果直接丢成空 404）
- 三层判定 MUST 保持互补：① 中间态（含"Run 内容已出现、进程还没挂上"）② 旧实例没停干净就不启动
  ③ 平台启动门。三者读的都是平台状态，MUST NOT 合并、也 MUST NOT 用其中一层声称覆盖了全部并发来源
- **旧实例没停干净时 MUST NOT 启动新进程**：那正是"同一配置留两个实例"的病根。此时 MUST 照常返回结构化结果：
  `started = false`、`startFailure` 写明原因、`transition[]` 以 `START_FAILED` 收尾
- **`startTimeoutMs` 的默认值 MUST 取 10000，与原生一致(HARD CONTRACT)**：内置
  `execute_run_configuration` 的 `timeout` 默认就是 `10000`（`ExecutionToolset` 字节码里的 `sipush 10000`）。
  调用方 MAY 覆盖它（例如某个配置的 before-launch 要先编译几十秒）
- **单次调用的时长 MUST 留在服务器的耐心之内(HARD CONTRACT)**：实测一个请求悬着超过约 **15 秒**，
  MCP Server 会先移除会话（`Pending StreamableHttp transport timed out` → `Removing session`），
  于是工具算完也送不回结果，客户端拿到的是 **HTTP 404 空响应**。实测边界：8 秒能正常送达、20 秒不能；
  **官方工具也一样**（`execute_run_configuration(waitForExit=true, timeout=30000)` 实测 30.1 秒后空 404）。
  注意 `rerun` 的总时长是三段相加（`stopTimeoutMs` + `startTimeoutMs` + 输出窗口），
  所以"慢停 + 慢启"的组合会超过这个上限；工具描述 MUST 提醒调用方这一点，
  并建议这类配置把 `stopTimeoutMs` 调小
- **启动超时的文案 MUST 点明原因**：先用 `RunConfiguration.getBeforeRunTasks()` 查这个配置配了几个
  before-launch 步骤；有就 MUST 说明"它们跑完之前平台不会产出 Run 内容，所以'还没启动'是正常现象"，
  并给出"调大 `startTimeoutMs`"的建议；没有就 MUST 说明另一种可能（启动被忽略 / 启动更慢）。
  文案 MUST 带上**实际生效的毫秒数**（MUST NOT 写死）
- 输出 JSON：

```json
{
  "configurationName": "console > dev",
  "mode": "RESTARTED",
  "started": true,
  "startFailure": null,
  "stoppedInstances": 1,
  "runningAfter": 1,
  "sessionId": "console > dev",
  "output": "[node] listening on http://127.0.0.1:3201\n",
  "fullOutputPath": "/…/system/tmp/ij_rerun__console_dev_<uuid>.log",
  "transition": [
    { "phase": "STOPPED", "detail": "stop requested for 1 instance(s); forced kills: 0; exit codes: 0", "elapsedMs": 180 },
    { "phase": "STARTED", "detail": "execution 3, pid 1003", "elapsedMs": 640 }
  ],
  "instances": [ ... ],
  "warnings": []
}
```

### 3.7 返回值通用约定

- 成功 MUST 用 `McpToolCallResult.text(摘要文本, 结构化JSON)`
- 失败 MUST 用 `McpToolCallResult.error(说明文本, 结构化JSON)`
- 文本摘要 MUST 是**一句人话**（agent 会同时看到文本与 JSON），MUST NOT 只是把 JSON 复读一遍
- **null 字段 MUST 保留为 JSON `null`，MUST NOT 省略该键**——agent 要能区分"键不存在"（工具出错）与"值为 null"（进程还没起来）
- 数组 MUST 输出 `[]` 而非省略

- **失败返回 MUST NOT 依赖 `structuredContent`**：MCP Server 只在"注册表开关 `mcp.server.structured.tool.output` 打开
  **且 `isError == false`**"时才输出 `structuredContent`（`McpSessionHandlerKt.toSdkToolCallResult` 的字节码里
  就是 `ifeq` + `ifne` 两道跳转）。所以错误结果在 wire 上只有 `isError: true` + `content[0].text`。
  推论：**错误文案 MUST 自洽**——配置名、当前状态、候选名、建议动作这些关键信息 MUST 全部写在文本里，
  MUST NOT 只放在 `structuredContent` 里（那样 agent 永远看不到）。
  失败仍 MUST 用 `McpToolCallResult.error(说明文本, 结构化JSON)`（成功路径同形，且服务器将来若放开错误结构化输出即可直接用）
- 上面"null 键保留"的约定只对**成功**返回有意义（错误返回根本没有结构化部分）

### 3.8 异常处理(HARD CONTRACT)

- 工具执行期间抛出的任何异常 MUST 被捕获并转成 `McpToolCallResult.error("Tool <name> failed: <原因>")`
- MUST NOT 把异常抛给 MCP Server（那样 agent 只能看到不可读的失败）

### 3.9 与内置 MCP Server 的契约对齐(HARD CONTRACT)

MCP Server 在派发任何工具之前，先解析"这次调用针对哪个项目"。解析依据只有两个：

1. header `IJ_MCP_SERVER_PROJECT_PATH`
2. 调用参数 `projectPath`

两者都没有时，它在工具代码之外直接返回 `Unable to determine the target project for the current MCP tool call`
——**即使当时只打开了一个项目**（实测：只开一个项目、不带参数调内置 `get_run_configurations` 同样被拒）。

- 三个工具的输入 schema **MUST** 声明可选的 `projectPath`（string），且 **MUST NOT** 放进 `required`
  （有 header 时它是可选的；内置 58 个工具里 54 个声明了它，全都不放进 `required`）
- `projectPath` 的**描述 MUST 与内置工具逐字一致**（见 `RunLifecycleSchemas.PROJECT_PATH_DESCRIPTION`），
  这样 agent 对"这个参数是什么"的认知与调内置工具时完全一致
- 三个工具 **MUST** 声明 `outputSchema`：既然返回了 `structuredContent`，就 MUST 声明它的结构
- `outputSchema` 里可为 null 的字段 MUST 写成 `["<类型>", "null"]`（单类型声明会让严格客户端在
  "进程还在跑所以 `exitCode` 为 null"这种正常返回上校验失败）

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

见 [`CHANGELOG.md`](./CHANGELOG.md)。当前版本 `0.0.1`。
