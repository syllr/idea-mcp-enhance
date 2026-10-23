<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE MCP Enhance Changelog

## [Unreleased]

## [0.1.3] - 2026-10-23

### Added

- **终端 MCP 工具（4 个）**：
  - `project_sdk_terminal` —— 在本项目 SDK 终端里执行命令，返回命令输出；每次调用创建一个真实 Terminal tab，支持 `input`、`timeoutMs`、`maxChars`、`workingDirectory`、`env`。
  - `execute_in_remote_host` —— 通过 Deployment SFTP/SSH 服务器创建真实远程 SSH 终端，支持 `loginShell`、`strictHostKey`、`workingDirectory` 和交互输入。
  - `terminal_io` —— 对已创建的本地/远程 tab 做增量读取、`tail` 命令段、`full` 全量读取和交互写入。
  - `list_terminals` —— 列出 `mcp_project_sdk`、`mcp_remote` 和 `user` 终端；只有带 tab id 且 `addressable: true` 的 MCP tab 能传给 `terminal_io`。
- **项目终端环境注入**：根据 IDE 配置把 Maven home、Node runtime/package manager、Go SDK（`GOROOT` / `GOPATH` / `GOBIN`）、Python SDK / venv 注入 Terminal 环境；用户显式 `env` 最后覆盖。
- **终端会话 registry**：按项目保存本地 command block 游标、远程命令 marker、tab 来源和最近一次命令状态，支持关闭检测与增量读取。
- **内置 `execute_terminal_command` 过滤**：用 `McpToolFilterProvider` 隐藏与 `project_sdk_terminal` 重叠的内置工具。

### Changed

- 终端错误结果统一把 machine-readable code 写进文案，格式为 `[code] message`；同时保留错误 JSON。
- 远程预检先检查 host / Key pair 私钥，再检查凭据，`deployment_host_missing` / `deployment_key_missing` 不再被“无凭据”掩盖。
- 新建 tab 只避开当前仍存活的 tab，IDE 重启后遗留的非存活 tab 不参与命名。
- 远程 `running` 改为 best-effort：同步完成/超时可判断，异步在无法确认时为 `null`；`exitCode` 仍为 `null`。
- `list_deployment_servers` 改名为 `list_server_deployments`：视角从 Deployment 改为 Server，每个 Server 的 `deployments[]` 只返回 deployment name（由 localPath 派生，不展开 mapping 路径）；Server 可以没有 deployment，此时仍可用于 `execute_in_remote_host`，但不能上传/下载。`upload_to_deployment` / `download_from_deployment` 改为只按全局唯一的 `deploymentName` 选择目标。没有显式 mapping 的 Server 不再被平台默认映射误报为已配置。
- 压缩终端、Run Configuration 和 Deployment 工具的描述，补充每次调用新建 tab、默认值、`list_terminals` 入口和异步轮询规则。

### Fixed

- 本地终端 `clear` / `reset` 后的增量读取游标、`tail` 截断提示和关闭检测。
- 远程 host key 严格模式、未知主机、网络不通、认证失败和 Key pair 缺失的分类与中文提示。
- Go `GOPATH` / `GOBIN` 注入：优先使用 IDE/环境配置，缺失时用 Go SDK 的 `go env` 兜底。
- Node package manager 路径识别，兼容可执行文件路径与目录路径。
- Python 终端优先使用项目/模块 SDK，其次项目目录已注册 SDK，最后 `.venv` 兜底。

### Notes

- 远程终端的 `exitCode` 永远为 `null`；异步远程的 `running` 也可能为 `null`，因为远程 shell 没有 command-finished 信号。
- 用户手动打开的 tab 只展示、不可寻址，避免 `terminal_io` 误写用户正在使用的交互式终端。
- Run Configuration 的生命周期仍由 Run 工具管理；`EXECUTE_IN_TERMINAL=true` 的配置可能以 `user` tab 出现在 `list_terminals` 中，但不能用 `terminal_io` 控制。
- 远程终端依赖 Ultimate 的 `remote-run` / Deployment 插件；本地终端依赖 Terminal 插件。

## [0.1.1] - 2026-10-20

### Removed

- **`edit_file` 工具** —— 它的职责（"写文件要经过 IDE，否则编辑器里的旧副本会在 `reformat_file` 时把新内容冲掉"）已经由
  `create_run_configuration` / `update_run_configuration` 在配置这条路上完整覆盖，单留一个通用写文件口子只会让"改配置该走
  哪边"变得含糊。改 `.run/*.run.xml` 请走那三个定义态工具。

## [0.1.0] - 2026-10-20

### Added

- **Run Configuration 定义态工具（4 个）** —— 补齐内置 MCP Server 只有"列 / 跑"、没有"建 / 改 / 删"的空白：
  - `inspect_run_configuration`（只读）—— 一个配置的全貌：类型、存储位置、能不能跑、`options` / `attributes` / `envs` / before-launch、原始 XML、当前活实例（pid / 状态 / 启动时间 / 日志路径）、历史运行（按开始时间倒序）、以及按 `[os@]base[@env]` 解析出的平台 / 环境维度。类型插件没装时仍可读：`type` 取文件里声明的值，`typeKnown: false`。
  - `create_run_configuration` —— 建**任意类型**的项目级配置。内容来源三选一：`cloneFrom`（复制现成的，最稳）、`configurationXml`、平台模板 + 补丁；写盘前用平台自己的 `readExternal` 校验，拼错的字段以 `unknownOptions` 报出来。
  - `update_run_configuration` —— 改字段（`options` / `attributes` / `envs` 补丁）**和改名**（`newName`）：文件跟着挪、别人对它的引用（before-launch / 复合配置）一起改、历史日志的键也迁移。
  - `delete_run_configuration` —— 在跑或被人引用时拒绝；`force = true` 才连引用一起清掉。
- **运行日志采集 + 历史**：进程启动的那一刻开始抓 stdout/stderr，落到 IDE 临时目录的 `ij_enhance_run__*.log`，退出时补一份 sidecar（pid / exitCode / 结束时间）。`list_running_configurations` 与 `inspect_run_configuration` 直接给出每个实例的日志路径，**历史运行按开始时间排序、只保留日志还在的那些**。
- `list_running_configurations` 的实例现在带 `state`（`running` / `terminating` / `starting`）与 `logFile`。

### Changed

- `list_running_configurations` 改成**索引**：去掉派生计数（配置数 = 数组长度）、恒定字段（`isTemplate` / `running` / `exitCode`）和不可信的 UI 文本（`displayName`）。详情走 `inspect_run_configuration`。
- `update_run_configuration` 改完会同时刷新三处：磁盘、编辑器里的 Document、RunManager 的内存对象 —— 实测过"文件改了但点 Run 跑的还是旧脚本"。

### Fixed

- **Shell 模板的机器级默认值**：从平台模板新建时会把本机绝对工作目录、`/bin/zsh`、终端模式、`EXECUTE_SCRIPT_FILE=true` 一起带进来。现在归一化为 `$PROJECT_DIR$`、空解释器、非终端模式，并按是否给了 `SCRIPT_TEXT` 决定 `EXECUTE_SCRIPT_FILE`。
- **Maven / Gradle 这类走 `XmlSerializer` 的类型读不了**：解析 XML 时保留了缩进空白节点，平台反序列化会 `Text as Element` 崩溃。改用平台自己的 `JDOMUtil`。
- **更新后编辑器里的旧副本**：文件在编辑器打开时只写磁盘，`read_file` 仍读到旧内容、用户一保存就冲掉新值。现在直接改 Document。
- **日志采集在 `rerun` 路径上抓不到**：我们自己的回调先 `startNotify()`，平台的 `processStarting` 被跳过。改成先接采集、再启动；用户手点 Run 的场景在 `processStarted` 补挂。
- 历史里混进正在跑的实例（按日志路径去重）。
- 类型插件缺失时（`Unknown`）不再阻断克隆 / 修改：按文件内容原样处理并给出 warning。

### Removed

- **`create_shell_run_configuration`** —— 它本来就是 `create_run_configuration` 的子集（只能建 Shell、没有平台校验）；现在统一走通用工具，实现只剩一份。

## [0.0.3] - 2026-10-17

### Fixed

- **Deployment 工具按名字找得到服务器了**：`serverName` 传 `list_deployment_servers` 里的 `servers[].name`（显示名）即可。此前它被当成服务器 id（UUID）去比，于是**任何**显式指定名字的 upload/download 都直接失败，报的还是自相矛盾的 `No deployment server named "X" … Existing: [X]`。
- **路径映射现在读的是你在 Deployment 设置里配的那份**：`list_deployment_servers` 的 `mappings[]`，以及 upload/download 用到的本地根，都按服务器 **id** 读取。此前查询用的是显示名，平台因此每次都返回一份当场补出来的"项目根 → 服务器根"默认映射（非空、看起来完全正常），你配的子目录 / 多条映射 / 自定义 deploy 路径实际上被忽略。
- **上传失败不再伪装成成功**：`upload_to_deployment` 与 `get_deployment_task_status` 在失败时（平台只给一个布尔结果，`failed` 计数甚至可能是 0）以前会输出 `Uploaded to "X": 0 operation(s) …`，读起来像传完了；现在明确写 `FAILED before any operation ran` / `FAILED after N operation(s)`，并指向 IDE 的 Deployment 日志找原因。

### Changed

- `list_deployment_servers` 的文本摘要逐行列出每台服务器的名字，并在默认那台上标 `(default)` —— 客户端即使不读 structuredContent，也能据此决定 `serverName`。
- Deployment 工具的可选依赖改为**链式 config-file**（`plugin.xml` → `mcpserver.xml` → `deployment.xml`）：没装 Deployment 插件时这些工具不注册，插件其余部分照常加载。顺带去掉了原先那段运行时存在性检查（它用的 `PluginManager.findEnabledPlugin` 是 `@ApiStatus.Internal`）。

### Removed

- 一条永远不可能触发的告警（"has no path mapping … nothing to work on"）：平台会给每台服务器补一份默认映射，这个列表几乎不可能为空；而"为空 ⇒ 无事可做"的推论本身也不成立。

## [0.0.2] - 2026-10-17

### Added

- **Deployment MCP 工具（4 个）** —— 把 IDE 的 Deployment（FTP/SFTP/WebDAV）能力交给 agent，补齐内置 MCP Server 完全没有的这一块：
  - `list_deployment_servers`（只读）—— 本项目可用的全部部署服务器（名称 / 项目级或应用级 / 协议 / host / port / rootFolder）、各自在本项目的路径映射，以及**项目级**的部署选项（`overwriteAll`、`deleteMissingItems`、确认开关、自动上传、排除掩码）与危险组合告警。只读 IDE 配置，不联网。
  - `upload_to_deployment`（破坏性）—— 把映射到的本地目录上传到服务器。`mode` 两取：`mirror`（**默认，会删掉远端多出来的文件**，使服务器与本地一致）/ `upload`（只传本地文件，保留远端多余）。
  - `download_from_deployment`（破坏性）—— 把服务器上映射目录的内容拉回本地，**以远端为准**。平台没有提供"非交互地跳过本地同名文件"的能力（IDE 默认弹框问，而 MCP 路径上没人能点），所以不暴露覆盖开关。
  - `get_deployment_task_status`（只读）—— 用句柄查一次传输的状态与计数，**永不启动任何任务**。
- **句柄式异步**：传输跑在 IDE 后台，可能超出工具的等待预算（默认 10000ms）。这时返回 `state: "in_progress"` 与一个**标识本次任务**的句柄——不是服务器名，因为同一台服务器会先后发生多次传输，用服务器名查会张冠李戴。同一台服务器也不会并发跑第二个传输：第二次调用会"加入"在跑的那个并把它的句柄返回。
- 传输全程走平台自己的 `TransferTask`：连接、路径解析（服务器 rootFolder + 路径映射）、进度与逐操作计数都由 IDE 负责；本插件不自建连接、不自己拼远端路径。
- 计数的口径与 IDE 一致：`transfer.details.transferred` 数文件，`transfer.details.deleted` 数**删除操作**——一个被整个删掉的目录算 1，哪怕它底下有几百个文件。

### Notes

- Deployment 工具**只在 Ultimate 上出现**：它们依赖内置的 Deployment（FTP/SFTP/WebDAV Connectivity）插件，而该插件依赖 `com.intellij.modules.ultimate`。插件不在时这 4 个工具不注册，本插件其余功能照常工作。
- 上传的删除确认框在 MCP 路径上会被强制关闭——模态对话框没人能点，会连同 EDT 一起把调用卡死。

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

[Unreleased]: https://github.com/syllr/idea-mcp-enhance/compare/0.1.1...HEAD
[0.1.1]: https://github.com/syllr/idea-mcp-enhance/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/syllr/idea-mcp-enhance/compare/0.0.3...0.1.0
[0.0.3]: https://github.com/syllr/idea-mcp-enhance/compare/0.0.2...0.0.3
[0.0.2]: https://github.com/syllr/idea-mcp-enhance/compare/0.0.1...0.0.2
[0.0.1]: https://github.com/syllr/idea-mcp-enhance/commits/0.0.1
