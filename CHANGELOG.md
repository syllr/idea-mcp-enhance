<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE MCP Enhance Changelog

## [Unreleased]

## [0.1.0] - 2026-10-14

### Added

- **Copy as Prompt** — 把编辑器选中的代码复制为 prompt 文本：`location:<路径>:<行号或起止行号>` + 带语言标识的代码围栏 + 选中内容原样。路径优先使用相对项目根的写法；选区恰好结束在某行行首时按真实覆盖的末行输出行号，不把未选中的行算进去。
- **Copy Diagnostics as Prompt** — 把当前文件全部的 ERROR / WARNING 复制为 prompt 文本：`location:<路径>` + 诊断列表（`[严重级别] line <行号>: <消息>` 与缩进的出错行原文）。诊断按行号 → 严重级别 → 消息稳定排序，相同诊断去重，inspection 描述里的 HTML 与换行会被净化成单行纯文本。
- 两个 action 的输出都以「换行 + 两个空格」结尾，使目标输入框（contenteditable 类实现）渲染出可见空行，避免粘贴后的后续输入被吞进代码块。
- 诊断采集走 document 级 `DocumentMarkupModel` + `HighlightInfo.fromRangeHighlighter`（与平台内部实现一致），只读 daemon 已经算好的高亮，不额外触发全量 inspection 分析。
- **Run Lifecycle MCP 工具**（需要 IDE 内置的 MCP Server 插件）：通过 `com.intellij.mcpServer.mcpToolsProvider` 扩展点注册，补齐内置 `ExecutionToolset` 缺失的 Run Configuration 生命周期能力。
  - `list_run_configurations_with_state`（只读）—— 列出全部 Run Configuration，附带实时状态：是否在跑 / 是否正在终止 / 是否已启动 / PID / 退出码 / executionId。
  - `get_run_configuration_status`（只读）—— 查单个配置状态。先精确匹配配置名，再忽略大小写；仍失败则返回错误并附带全部候选名，让 agent 一次就能纠正。
  - `stop_run_configuration`（破坏性）—— 停掉该配置的**所有**运行实例，修掉"同一配置重复启动会起两个实例"的老问题；支持等待真正退出与超时后强制 kill。
  - `rerun_run_configuration`（破坏性）—— 等价于 IDE 的 Rerun 按钮：在跑就重启，没在跑就启动；走 `ExecutionManager.restartRunProfile`，与界面按钮同一条代码路径。
- MCP 增强完全走 IDE 的 `ProcessHandler`：语言无关（不匹配命令行、不依赖端口）、不改项目启动命令（不需要给每个技术栈写一遍启动器包装）。
- MCP Server 作为**可选依赖**（`<depends optional="true" config-file="mcpserver.xml">`）：没装 / 没启用 MCP Server 的 IDE 上插件照常工作，只是不会注册 MCP 工具。
- 测试：`PromptFormatTest`（11 例，纯函数格式契约）+ `RunLifecycleSchemaTest`（21 例，MCP 工具描述符 / 输入参数解析 / 输出 JSON 契约）+ `EditorDiagnosticsTest`（4 例，`BasePlatformTestCase`）。
- GitHub Actions CI（`.github/workflows/build.yml`）：`test` / `build` / `verify`，以及打 `v*` tag 时的 `publish`。
- 项目元文档：`AGENTS.md`（开发约束与平台 API 踩坑）、`SPEC.md`（行为契约）、`DESIGN.md`（分层与关键决策）。

### Known limitations

- 目标平台被抬到 **2026.2（build 262）及以上**：MCP Server 是该版本的**内置**插件，没有发布到 Marketplace，无法作为独立依赖，因此编译它的 API 会把整个插件的下限一起抬上去。上游 `copy-as-prompt` 本身只需要 202.1+。
- **`EditorDiagnosticsTest` 暂时禁用**：IntelliJ Platform 2026.2.3 的测试沙箱起不来（打开测试项目时 post-startup 阶段报 `Cannot create extension (class=Z.Z.Z.Z.Z) [Plugin: com.intellij.modules.ultimate]` → `Cannot find suitable constructor ... expected (), (CoroutineScope), (Application), or (Application, CoroutineScope)`）。这是平台内部类（`product-backend.jar`）的问题，与本插件无关：把同样代码指向 2026.2.3 的上游 `copy-as-prompt` 失败完全一致；且 2026.2 已不再发布 IDEA Community 版，无法绕开。诊断采集的实际行为请用 `./gradlew runIde` 手工验证。
- 四个 MCP 工具只在 Open Code 显式申请后才会真正停 / 重启进程；插件自身不做任何后台轮询或自动重启。
- `rerun_run_configuration` 用配置**名字**定位，不支持 `file:line` 代码位置（内置 MCP 的 `get_run_configurations(filePath = ...)` 已能给出可执行点，组合使用即可）。

[Unreleased]: https://github.com/syllr/idea-mcp-enhance/compare/0.1.0...HEAD
[0.1.0]: https://github.com/syllr/idea-mcp-enhance/commits/0.1.0
