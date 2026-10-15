<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE MCP Enhance Changelog

## [Unreleased]

## [0.0.1] - 2026-10-14

### Added

- **Copy as Prompt** — 把编辑器选中的代码复制为 prompt 文本：`location:<路径>:<行号或起止行号>` + 带语言标识的代码围栏 + 选中内容原样。路径优先使用相对项目根的写法；选区恰好结束在某行行首时按真实覆盖的末行输出行号，不把未选中的行算进去。
- **Copy Diagnostics as Prompt** — 把当前文件全部的 ERROR / WARNING 复制为 prompt 文本：`location:<路径>` + 诊断列表（`[严重级别] line <行号>: <消息>` 与缩进的出错行原文）。诊断按行号 → 严重级别 → 消息稳定排序，相同诊断去重，inspection 描述里的 HTML 与换行会被净化成单行纯文本。
- 两个 action 的输出都以「换行 + 两个空格」结尾，使目标输入框（contenteditable 类实现）渲染出可见空行，避免粘贴后的后续输入被吞进代码块。
- 诊断采集走 document 级 `DocumentMarkupModel` + `HighlightInfo.fromRangeHighlighter`（与平台内部实现一致），只读 daemon 已经算好的高亮，不额外触发全量 inspection 分析。
- **Run Lifecycle MCP 工具**（需要 IDE 内置的 MCP Server 插件）：通过 `com.intellij.mcpServer.mcpToolsProvider` 扩展点注册，补齐内置 `ExecutionToolset` 缺失的 Run Configuration 生命周期能力。四个工具：
  - `list_running_configurations`（只读）—— 回答"**现在到底什么在跑**"：只返回有活进程的配置，且每个配置的 `instances[]` 也只含活实例（已结束的 Run 内容——Run 工具窗口留下的标签页——会被剔除）。每个实例给 `executionId` / `displayName` / PID / 是否正在终止 / 是否已收到启动通知。参数只有 `projectPath`：模板不是实例、永远不会在跑，所以不需要开关；想知道"有哪些配置"用内置的 `get_run_configurations`。
  - `stop_run_configuration`（破坏性）—— 停掉该配置的**所有**运行实例，修掉"同一配置重复启动会起两个实例"的老问题；支持等待真正退出与超时后强制 kill。
  - `rerun_run_configuration`（破坏性）—— 等价于 IDE 的 Rerun 按钮：在跑就重启，没在跑就启动。**同步等到新进程真的被创建**（并采集一段启动输出）才返回，回传 `started` / `startFailure` / `sessionId` / `output` / `fullOutputPath`，以及状态流转 `transition[]`（`STOPPED` → `STARTED`｜`START_FAILED`，各带耗时，旧实例的退出码写在第一个节点里）。另有 `startTimeoutMs`（默认 **10000**，**与原生 `execute_run_configuration` 的 `timeout` 默认值一致**）：常见配置要先跑构建（`npm install` / `mvn compile` / `go build`）或另一个 run configuration，而平台在 before-launch 步骤跑完之前**不会产出 Run 内容**，所以这个等待窗口可以调大。**启动超时的文案会点明原因**：先用 `RunConfiguration.getBeforeRunTasks()` 查该配置配了几个 before-launch 步骤——有就说明"它们跑完之前'还没启动'是正常现象"并建议调大 `startTimeoutMs`，没有就说明另一种可能（启动被忽略 / 启动更慢）；文案带的是**实际生效的毫秒数**。
- **`rerun_run_configuration` 支持异步提交**（`waitForStart = false`，参数名对齐原生 `execute_run_configuration` 的 `waitForExit`）：只提交启动请求就立刻返回（实测 0.04 秒），`started` / `runningAfter` 为 `null`（"不知道"，与仓库既有约定"值为 null = 进程还没起来"一致）、`transition[]` 以新增的 `START_SUBMITTED` 收尾、`warnings[]` 说明结果未知并指引用 `list_running_configurations` 轮询。这是给"before-launch（构建 / 另一个配置）比 MCP Server 会话耐心还长"的配置准备的：那种情况下同步等待只会把结果丢成空 404，只有"提交 + 自己轮询"可行。默认仍是同步（`waitForStart = true`），语义不变。
- **启动前会复刻平台自己的"能不能跑"校验**：平台在 `ExecutionManagerImpl.executeConfiguration` 里先问 `RunManagerImpl.canRunConfiguration`（= `settings.checkSettings(executor)`），为 false 时**弹 `RunDialog.editConfiguration` 模态对话框**——agent / 无头场景里没人点它，EDT 会被永久占住，工具调用再也回不来（`jstack` 实测抓到这个栈）。现在改用**同一个公开方法**提前问一次，把 `IndexNotReadyException`（IDE 还在建索引，刚打开大项目时很常见）与 `RuntimeConfigurationError`（配置本身有致命问题）转成立刻、可读的结构化错误。另外还会**主动判断"现在是不是有模态对话框在等用户"**（扫本 JVM 里所有可见且带模态语义的 `Dialog`，比 `checkSettings` 更通用：它拦的是"别的对话框占着 EDT"），命中就立刻返回"**这需要用户去 IDE 处理**"——文案明确要求 AI 转告用户去 IDE 里完成那个对话框，而不是自己重试。**判断的时机也修了**：这三道判断（对话框 / `checkSettings` / `isStarting`）现在在**停旧实例之前**先问一次、**真正提交之前**再问一次——之前只在提交前问，于是会"先把在跑的应用杀掉，再告诉用户需要你去 IDE 处理"（实测重排后同样的场景 0.02 秒返回错误，且旧实例仍然活着）。判断**不依赖焦点**（`DialogWrapper.findInstanceFromFocus()` 读 `getFocusOwner()`，IDE 窗口没被聚焦时是 null，实测会落空），而是扫本 JVM 里可见且带模态语义的 `Dialog`。实测：用内置工具触发平台的 "Edit Configuration" 后，我们的 rerun **0.03 秒**就返回这条信息（靠超时兜底则要等满 10000ms），而 `list_running_configurations` 仍可用。
- **`stop_run_configuration` 修掉一个假成功**：`rerun` 异步模式（`waitForStart = false`）提交完就返回，此时 Run 内容可能还没建、进程还没挂上；这时 `stop` 会报 "not running; nothing to stop" 并返回成功——**但进程随后就起来了**（实测：stop 返回成功 3 秒后进程在跑、端口占着）。现在先用 `hasPendingRunContent` / `ExecutionManager.isStarting(env)` 判断"有启动在飞"，再**有界等待**（2000ms）让它挂上进程后照常停；等不到就如实说明"启动仍在飞、什么都没停、稍后可能起来"，不再谎报。
- **新增 `create_shell_run_configuration`（第四个工具）**：把"增"补齐（现有是查 / 停 / 起）。只建 **Shell Script** 类型（`ShConfigurationType`），用途是让 agent"用项目环境跑命令"：Shell 配置自带工作目录、环境变量、解释器。实现是**直接写 `.run.xml` 文件**（纯字符串生成，不调平台创建 API，也就不需要 `com.jetbrains.sh` 依赖）：文件形状与 IDE 自己写的字节一致，写完等文件监听注册（有界等待），注册上就用平台 `checkSettings` 问能不能跑。三种建法三选一：**`scriptPath`** / **`scriptText`** / **克隆已有 Shell 配置**（读源文件改名）。只创建**项目级**配置（落点固定 `.run/<配置名>.run.xml`，不开放落点入参；脚本恒继承 IDE 环境、恒跑在 Run 工具窗口）。重名 / 目标文件已存在会拒绝并给出建议名。**没有 delete 工具**：删除是用户在 IDE 里的动作。
- 四个工具的 schema 都声明了可选的 `projectPath`（描述与内置 MCP 工具**逐字一致**；客户端配了 `IJ_MCP_SERVER_PROJECT_PATH` header 时，MCP Server 会在把 schema 发给 agent 之前把这个参数摘掉）与 `outputSchema`（可为 null 的字段写成 `["<类型>", "null"]`）。前者让没配 header 的客户端也能用这些工具；后者让客户端能校验 `structuredContent`。
- MCP 增强完全走 IDE 的 `ProcessHandler`：语言无关（不匹配命令行、不依赖端口）、不改项目启动命令（不需要给每个技术栈写一遍启动器包装）。
- MCP Server 作为**可选依赖**（`<depends optional="true" config-file="mcpserver.xml">`）：没装 / 没启用 MCP Server 的 IDE 上插件照常工作，只是不会注册 MCP 工具。
- 测试：`PromptFormatTest`（11 例，纯函数格式契约）+ `RunLifecycleSchemaTest`（48 例，工具描述符 / 输入参数解析 / 输出 schema / 与内置工具的逐字对齐 / 输出 JSON 契约）+ `EditorDiagnosticsTest`（4 例，`BasePlatformTestCase`；当前全部跳过，原因见下「已知限制」）。
- GitHub Actions CI（`.github/workflows/build.yml`）：`test` / `build` / `verify`，以及打 `v*` tag 时的 `publish`。
- 项目元文档：`AGENTS.md`（开发约束与平台 API 踩坑）、`SPEC.md`（行为契约）、`DESIGN.md`（分层与关键决策）。

### Changed

- `list_run_configurations_with_state` 改名为 **`list_running_configurations`**，并收成单一职责：只返回**正在跑**的配置、每个配置只含**活实例**，参数只剩 `projectPath`（去掉了 `includeTemplates` 与"是否只看在跑的"两个开关——模板不是实例、永远不会在跑，本来就会被"只返回在跑的"排除）。输出去掉了恒等于配置数的 `count`，只留 `runningCount`。
- 启动不再走 `ExecutionManager.restartRunProfile`（fire-and-forget，调用后立刻快照必然读到 `runningAfter = 0`），改为内置 MCP Server 用的那条路：`ProgramRunnerUtil.executeConfigurationAsync(environment, false, true, callback)` + `ProgramRunner.Callback`。
- 新进程的输出日志落在 IDE 的 `system/tmp/ij_rerun__<配置名>_<uuid>.log`，与内置工具的 `ij_run__*.log` 同目录、前缀不同，便于区分来源。

### Removed

- `get_run_configuration_status`（第四个工具，按名字查单个配置的状态）。实测判定冗余：它返回的结构与 `list_running_configurations` 的单个元素**完全同形**；"名字纠错"`stop` / `rerun` 本来就有（同一套"精确 → 忽略大小写 → 列候选名"）；"看实时状态"由 `list_running_configurations` 覆盖（项目里并发的作业本来就不多，全量拉一次很便宜）。它剩下的唯一价值只是"省一次全量拉取"，不值一个常驻工具位。

### Fixed

- `rerun_run_configuration` 的返回值不再自相矛盾。之前它在发出启动请求后**立刻**做快照，于是 `runningAfter` 恒为 0、`instances[]` 里只有旧的已死实例，而人话摘要却写着"已启动"——agent 最自然的读法就是"启动失败"。现在**同步等到新进程真的被创建**才返回，`runningAfter` 反映的是真实数量。
- `rerun_run_configuration` 不再在启动后用一次新的快照：IDEA 会复用同一个 tab 的 `RunContentDescriptor`，那次快照可能读到"新 `executionId` + 上一次的 `ProcessHandler`"的中间态（表现为 `running=false`、pid 是旧的，而端口其实已被新进程占着）。现在改为轮询到"新实例确实在跑"才收工，并用这份收敛后的快照回报。
- `rerun_run_configuration` 的 `STARTED` 节点不再把"返回前采集启动日志"的时间算成启动耗时：之前摘要会写成"启动用了 2 秒"，而进程其实 100ms 就起来了。`elapsedMs` 现在只算到"进程真的被创建"。
- 四个工具都补上了 `outputSchema`。它们本来就返回 `structuredContent`，但没声明结构，严格客户端无法校验。

### Known limitations

- 目标平台被抬到 **2026.2（build 262）**：MCP Server 是该版本的**内置**插件，没有发布到 Marketplace，无法作为独立依赖，因此编译它的 API 会把整个插件的下限一起抬上去。上游 `copy-as-prompt` 本身只需要 202.1+。
- **`EditorDiagnosticsTest` 暂时禁用**：IntelliJ Platform 2026.2.3 的测试沙箱起不来（打开测试项目时 post-startup 阶段报 `Cannot create extension (class=Z.Z.Z.Z.Z) [Plugin: com.intellij.modules.ultimate]` → `Cannot find suitable constructor ... expected (), (CoroutineScope), (Application), or (Application, CoroutineScope)`）。这是平台内部类（`product-backend.jar`）的问题，与本插件无关：把同样代码指向 2026.2.3 的上游 `copy-as-prompt` 失败完全一致；且 2026.2 已不再发布 IDEA Community 版，无法绕开。诊断采集的实际行为请用 `./gradlew runIde` 手工验证。
- 四个 MCP 工具都只在被显式调用时才动作（停 / 重启 / 建配置）；插件自身不做任何后台轮询或自动重启。
- **`rerun_run_configuration` 的可重入语义全部建立在平台自己的状态上**（不维护任何插件侧内存状态）：① 中间态（启动中 / 停止中，含"Run 内容已出现、进程还没挂上"）直接拒绝；② 旧实例没停干净就不启动新进程；③ 发起前先过平台自己的门 `ExecutionManager.isStarting(environment)` —— 因为 `ProgramRunnerUtil.executeConfigurationAsync` 对"同配置正在启动"的第二次提交是**静默丢弃**（回调都不装）。平台永远不会起两个实例，所以这三道判定不是为了"防双实例"，而是把"静默丢弃 / 静默悬挂"换成立刻、精确、可自洽的错误。不同配置并发 rerun 仍允许。
- **单次调用时长必须远小于 MCP Server 的会话耐心**（实测约 15 秒）：请求悬着超过它，服务器会先移除会话，工具算完也送不回结果 → 客户端拿到 HTTP 404 空响应（实测 8 秒能送达、20 秒不能）。因此 `startTimeoutMs` 的默认值取 **10000**——**与原生 `execute_run_configuration` 的 `timeout` 默认值一致**；调用方可以覆盖它（比如某个配置的 before-launch 要先编译几十秒），但整次调用是三段相加，慢停 + 慢启会超过上限。
- `rerun_run_configuration` 用配置**名字**定位，不支持 `file:line` 代码位置。
- **内置 `get_run_configurations` 的 `filePath` 模式未实现**：给定一个文件、列出该文件里可执行的入口点（IDE 行号槽上出现 Run 图标的位置），那需要整套 PSI / `RunConfigurationProducer` / `ConfigurationFromContext` 能力。本插件刻意不碰 PSI（prompt 那半也不碰），因此这块仍由内置工具承担，两个工具组合使用即可。

[Unreleased]: https://github.com/syllr/idea-mcp-enhance/compare/0.0.1...HEAD
[0.0.1]: https://github.com/syllr/idea-mcp-enhance/commits/0.0.1
