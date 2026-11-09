# AGENTS.md

JetBrains IDE 插件（Kotlin）：把 IDE 里的信息变成 **prompt 文本**复制到剪贴板，同时给 IDE 内置的 MCP Server 补上 Run
Configuration 的生命周期能力（列出 / 查询 / 停止 / 重启）。

prompt 那一半不联网、不起进程、不读配置文件；MCP 那一半只在用户显式启用 MCP Server 并调用工具时才动进程。

## 项目级元文档

| 文档                         | 关注                                 | 何时读                                    |
|------------------------------|--------------------------------------|-------------------------------------------|
| **[AGENTS.md](./AGENTS.md)** | 怎么开发（流程规则、强制约束、踩坑） | **第一份必读** — 任何任务开始前           |
| **[SPEC.md](./SPEC.md)**     | 必须满足什么（输出契约、硬规则）     | 改输出格式 / 改 MCP 返回 / Code Review 前 |
| **[DESIGN.md](./DESIGN.md)** | 怎么实现（分层、关键决策、平台坑）   | 加 action / 加工具 / 排查平台 API 问题时  |

## STACK

- Gradle 9.3.1 · JDK 21 · Kotlin 2.3.20 · IntelliJ Platform Gradle Plugin **2.19.0**（不能退回 2.14.0：它解析不了
  2026.2 的 `module-descriptors.jar`，连 `:test` 的依赖都算不出来；2026.3 用同一个版本也可用）
- 目标平台 **2026.3**（build 263.7307.200）：`pluginSinceBuild=263`、`pluginUntilBuild=263.*`。
  内置 MCP Server 的 `McpToolDescriptor` / `McpToolCategory` 构造器在 263 加了字段，与 262 二进制不兼容，
  所以这一版只支持 2026.3
- **`main` 的 0.1.9 面向 2026.2；本分支是同一版本号的 2026.3 变体**。只做本地 / 分支产物；
  要发布到 Marketplace 前必须换一个版本号（0.1.9 已被 main 占用），并同时补两个 CHANGELOG 的对应段落
- **IDEA Community 在 2026.2 已停发**（`ideaIC:2026.2.3` 不存在），只能用 `intellijIdea(...)` 拉 Ultimate
- 运行时依赖：**无第三方库** —— 只用 IntelliJ Platform API + 内置 `com.intellij.mcpServer` + 平台自带的
  `kotlinx-serialization-json`
- 测试依赖：JUnit 4.13.2、opentest4j 1.3.0、IntelliJ Platform TestFramework
- 静态分析：Qodana（`jetbrains/qodana-jvm-community:2024.3`，Gradle plugin `2025.3.1`）+ Kover 0.9.5 + CodeCov
- `CHANGELOG.md` 由 `org.jetbrains.changelog` 插件（`patchChangelog` 任务）管理，**不要手改版本段落结构**
- **`CHANGELOG.en.md` 是 `<change-notes>` 的唯一来源**（英文面向商店，中文 `CHANGELOG.md` 面向仓库）；**发版时两个文件都要加
  一段**，漏了英文那段构建会直接失败。`<description>` 同理，直接写在 `plugin.xml` 里且是英文

### 五个容易混淆的"名字"

| 位置                                     | 值                                    | 作用                                                         |
|------------------------------------------|---------------------------------------|--------------------------------------------------------------|
| `plugin.xml` `<id>`                      | `com.shenyuanlaolarou.ideamcpenhance` | 插件 id（市场唯一标识）。**发布后永久不可改**                |
| `gradle.properties` `pluginName`         | `IDE MCP Enhance`                     | 构建时 patch 到 `plugin.xml` `<name>` 的**显示名**           |
| `gradle.properties` `platformPlugins`    | `com.intellij.mcpServer`              | 内置 MCP Server 插件。**ID 大小写敏感，S 是大写**            |
| `settings.gradle.kts` `rootProject.name` | `idea-mcp-enhance`                    | 决定产物名 `build/distributions/idea-mcp-enhance-<版本>.zip` |
| Kotlin 包名                              | `com.shenyuanlaolarou.ideamcpenhance` | 与插件 id 保持一致                                           |

> `platformBundledPlugins` 里写成 `com.intellij.mcpserver`（小写 s）会报
> `Could not find bundled plugin with ID: 'com.intellij.mcpserver'`——Gradle 插件按 **精确大小写**匹配。

## STRUCTURE

源码根: `src/main/kotlin/com/shenyuanlaolarou/ideamcpenhance/`

**两个互不依赖的能力包**（`mcp/` 内部再按能力分组），各自分层：

```
├── prompt/                        ← 能力一：复制 prompt（与上游 copy-as-prompt 逐字节一致）
│   ├── format/PromptFormat.kt     纯函数：输出格式的唯一实现处
│   └── actions/                   平台接线：取 IDE 数据、判定 enabled、写剪贴板
│       ├── CopyAsPromptAction.kt
│       ├── CopyDiagnosticsAction.kt
│       └── EditorPromptContext.kt
└── mcp/                           ← 其余 MCP 能力（都只要 MCP Server）
    ├── 运行态（Run Lifecycle）
    │   ├── RunLifecycleModels.kt        纯数据 + 纯函数：状态快照 → JSON
    │   ├── RunLifecycleSchemas.kt       工具描述符 / 输入 schema / 参数解析
    │   ├── RunConfigAccess.kt           平台接线：RunManager / RunContentManager / ProcessHandler
    │   ├── RunLifecycleToolsProvider.kt 运行态 3 个 McpTool + 异常兜底
    │   ├── RunLogModels.kt              纯：日志键 / 文件名 / 历史条目 JSON
    │   ├── RunLogAccess.kt              平台：启动即采集输出、写 sidecar、扫历史、清理
    │   ├── RunConfigNaming.kt           纯：`[os@]base[@env]` 解析
    │   ├── RunKind.kt                   纯：job / server 判定
    │   └── RunConfigurationStamping.kt  给 Run 会话盖配置名
    ├── 定义态（inspect）
    │   ├── RunConfigDefinitionModels.kt       纯：inspect 的全貌 → JSON
    │   ├── RunConfigDefinitionAccess.kt       平台：读 `.run.xml`、解析 options/envs/method
    │   ├── RunConfigDefinitionSchemas.kt      `inspect` 的描述符
    │   └── RunConfigDefinitionToolsProvider.kt
    ├── Remote Process / Deployment
    │   ├── RemoteProcessRunConfiguration.kt   配置类型 + SettingsEditor
    │   ├── RemoteProcessRunProfileState.kt    Run 状态：同步 + SSH 启动
    │   ├── RemoteProcessFinder.kt / RemoteProcessHosts.kt
    │   ├── RemoteProcessSync.kt / RemoteSshClient.kt
    │   ├── RemoteProcessToolsProvider.kt      deploy_remote_process / list_remote_processes
    │   ├── RemoteProcessDeploySchemas.kt
    │   ├── DeploymentModels.kt / DeploymentSchemas.kt
    │   ├── DeploymentAccess.kt               全插件唯一 import webDeployment 的文件
    │   └── DeploymentToolsProvider.kt        get_ssh_servers / sync_deployments
    ├── 项目环境（get_project_env）
    │   ├── TerminalEnv.kt                    纯聚合：5 个 provider 按固定顺序拼 PATH
    │   ├── JavaTerminalEnv.kt / NodeTerminalEnv.kt / GoTerminalEnv.kt
    │   ├── MavenTerminalEnv.kt / PythonTerminalEnv.kt
    │   ├── ProjectEnvSchemas.kt
    │   └── ProjectEnvToolsProvider.kt
    └── 公共
        ├── McpToolBase.kt                    异常兜底 / 参数错误 / 项目解析
        └── LocalPath.kt
```

**运行态**：`list_running_configurations` / `stop_run_configuration` / `rerun_run_configuration`，
靠 `ProcessHandler` 而不是命令行匹配。

**定义态**：只有 `inspect_run_configuration`（只读）。配置的新增 / 修改 / 删除回 IDE 界面；
可重复的流程走 job skill，不由 agent 代写 `.run/`。

**Remote Process / Deployment**：`deploy_remote_process` / `list_remote_processes` 是模型可见工具；
`get_ssh_servers` / `sync_deployments` 是隐藏的数据通道。文件传输与远端命令走
`RemoteSshClient` / `RemoteProcessSync` 的 SSH 实现，deployment 仅用于拿凭据与路径映射。

**项目环境（`get_project_env`）**：`opencode-idea` 在 `shell.hook("create.before")` 里直接调用，
把 IDE 里配的 SDK / runtime 变成 `{ PATH, variables }`，由那边前置拼到 OpenCode 原生 shell 的 PATH 上。
它不是给模型用的工具。`project_sdk_terminal` 与本地 `terminal_read` 分支已经整体移除。

| 层                                    | 允许                                                | 禁止                                      | 测试方式                                            |
|---------------------------------------|-----------------------------------------------------|-------------------------------------------|-----------------------------------------------------|
| `prompt/format/`                         | 字符串操作                                          | 平台 import、IO、环境变量                 | 纯 JUnit 4（秒级）                                  |
| `mcp/RunLifecycleModels` / `Schemas`     | 字符串、JsonObject、mcpserver 的 descriptor 类型    | 平台 import、IO                           | 纯 JUnit 4（秒级）                                  |
| `prompt/actions/`、`mcp/RunConfigAccess` | 平台 API                                            | 字符串格式化 / 手写 JSON                  | `BasePlatformTestCase`（见「已知阻塞」）或 `runIde` |
| `mcp/RunLifecycleToolsProvider`          | 编排：解析参数 → 调接线层 → 包 `McpToolCallResult`  | 直接操作 `ProcessHandler`                 | 描述符 + 参数解析有单测                             |
| `mcp/DeploymentModels` / `Schemas`       | 字符串、JsonObject、mcpserver 的 descriptor 类型    | 平台 import、IO、**webDeployment import** | 纯 JUnit 4（秒级）                                  |
| `mcp/DeploymentAccess`                   | webDeployment + 平台 API（**全插件唯一允许处**）    | 字符串格式化 / 手写 JSON                  | 只能 `runIde` 手工验证（需要真的服务器）            |
| `mcp/DeploymentToolsProvider`            | 编排（前提由 config-file 把守，**没有**运行时检查） | 在 EP 注册之前碰 webDeployment 类型       | 描述符 / 纯函数有单测                               |
| `mcp/RemoteProcess*`                     | 平台 API + `com.intellij.ssh`                       | 字符串格式化 / 手写 JSON                  | 纯函数单测 + `runIde` 手工验证                      |
| `mcp/ProjectEnv*` / `*TerminalEnv`       | 平台 API                                            | 字符串格式化 / 手写 JSON                  | 纯 JUnit 4（`ProjectEnvTest`）                      |

测试根: `src/test/kotlin/com/shenyuanlaolarou/ideamcpenhance/`

| 测试                                   | 类型                   | 覆盖                                                                                        | 状态            |
|----------------------------------------|------------------------|---------------------------------------------------------------------------------------------|-----------------|
| `prompt/format/PromptFormatTest`       | 纯 JUnit 4（11 例）    | 两个 prompt 格式契约 + 稳定排序                                                             | ✅ 通过         |
| `mcp/RunLifecycleSchemaTest`           | 纯 JUnit 4（45 例）    | 工具集合 / 描述符 / 输入 schema / 输出 schema / 与内置工具的逐字对齐 / 参数解析 / 输出 JSON | ✅ 通过         |
| `mcp/DeploymentSchemaTest`             | 纯 JUnit 4（6 例）     | deployment 描述符 / 风险提示 / 输出 JSON 形状                                               | ✅ 通过         |
| `mcp/RunLogAndNamingTest`              | 纯 JUnit 4（24 例）    | 命名文法 / 日志键与文件名 / 历史条目 null 键 / 列表精简形状 / 定义态 schema                 | ✅ 通过         |
| `mcp/RunLogTailTest`                   | 纯 JUnit 4（5 例）     | 日志尾部预览的截断与边界                                                                    | ✅ 通过         |
| `mcp/RunKindTest`                      | 纯 JUnit 4（8 例）     | job / server 判定                                                                           | ✅ 通过         |
| `mcp/RemoteProcessKindTest`            | 纯 JUnit 4（4 例）     | `RemoteProcess` 恒为 server                                                                 | ✅ 通过         |
| `mcp/LocalPathTest`                    | 纯 JUnit 4（2 例）     | 本地路径规范化                                                                              | ✅ 通过         |
| `mcp/ProjectEnvTest`                   | 纯 JUnit 4（2 例）     | `get_project_env` 的返回形状 / provider 固定顺序                                            | ✅ 通过         |
| `prompt/actions/EditorDiagnosticsTest` | `BasePlatformTestCase` | 诊断采集能从 markup model 捞出编译错误                                                      | ⛔ 禁用（见下） |

### 测试纪律：不要随便写断言

写之前先问一句：**这条断言在改一次文案之后还会成立吗？** 会红就说明它测的是文字，不是契约。

- **schema / 输出 JSON / 参数解析**这类对外契约要测：它们是别的代码按名字取用的东西。
- **工具描述、错误文案、菜单文案**不测：改一次措辞就红一片，测了也证明不了什么。
- **别拿"描述里必须出现某个词"当契约**：既漏（改名就红）又吵（重排就红）。要盯的是**行为**
  —— 注入了几段、返回的 JSON 有没有某个键、该停的有没有停。
- 新增工具 / 能力 / 文案**不补断言**；已有的文案断言该删就删。
- 判断不了就不写。覆盖率不是目标，**红得让人烦**才是问题。

## WHERE TO LOOK

| 想做的事                                 | 改哪里                                                                                                                                                                                              |
|------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 改 prompt 输出格式                       | `prompt/format/PromptFormat.kt` + 同步 `PromptFormatTest` + `SPEC.md §1/§2`                                                                                                                         |
| 改菜单项文案 / 出现位置                  | `src/main/resources/META-INF/plugin.xml` 的 `<action>` / `<add-to-group>`                                                                                                                           |
| 改 action 的启用条件                     | 各 action 的 `update()`                                                                                                                                                                             |
| 改文件路径写法（相对/绝对）              | `prompt/actions/EditorPromptContext.kt` 的 `promptFilePath()`                                                                                                                                       |
| 改诊断采集范围 / 过滤规则                | `prompt/actions/EditorPromptContext.kt` 的 `collectDiagnostics()`（先读 `DESIGN.md §3.1`）                                                                                                          |
| 改 MCP 工具的名字 / 描述 / 入参          | `mcp/RunLifecycleSchemas.kt` 的三个 `*Descriptor` + `RunLifecycleSchemaTest`                                                                                                                        |
| 改 MCP 工具返回的 JSON 字段              | `mcp/RunLifecycleModels.kt` 的 `toJson()`                                                                                                                                                           |
| 改 MCP 的停止实现                        | `mcp/RunConfigAccess.kt` 的 `stopHandler` / `stopDescriptors`                                                                                                                                       |
| 改 MCP 的启动 / 重启实现                 | `mcp/RunConfigAccess.kt` 的 `startRunAndAwait`（`ProgramRunner.Callback` + 输出采集）                                                                                                               |
| 改启动后回传的日志 / 状态流转            | `mcp/RunConfigAccess.kt` 的 `RunOutputCollector` + `mcp/RunLifecycleModels.kt` 的 `RerunReport`                                                                                                     |
| 改 rerun / stop 的可重入语义             | `RunLifecycleModels.kt` 的 `rerunBlockedReason` / `startBlockedReason` + `RunConfigAccess.kt` 的 `hasPendingRunContent` + 平台启动门 |
| 改 `projectPath` / `outputSchema` 的对齐 | `mcp/RunLifecycleSchemas.kt` 的 `PROJECT_PATH_DESCRIPTION` + 三个 `*Descriptor` 的第 7 个参数                                                                                                       |
| 加一个新 MCP 工具                        | `mcp/RunLifecycleToolsProvider.kt` 加一个 `BaseRunTool` 子类 + 在 `getTools()` 里注册                                                                                                               |
| 改 `get_ssh_servers` 的字段        | `mcp/DeploymentModels.kt` 的 `DeploymentServerCredentials.toJson()` + `mcp/DeploymentSchemas.kt` + `DeploymentSchemaTest`                                                                          |
| 改它读哪些 server / 凭据                  | `DeploymentAccess.kt`（**唯一允许 import webDeployment 的文件**）                                                                                                                                    |
| 改项目环境变量的 provider / 顺序         | `mcp/TerminalEnv.kt`（枚举顺序 + 聚合）+ 各 `*TerminalEnv.kt` + `mcp/ProjectEnvSchemas.kt`（返回契约）                              |
| 改 `get_project_env` 的调用方            | `opencode-idea` 的 `src/plugin.js`：`loadProjectEnv` / `shell.hook("create.before")` / `env-unready` 状态                          |
| 改远程执行（已移出本插件）               | `opencode-idea` 的 `src/remote/`（`hosts.js` 三级合并 / `ssh.js` 执行 / `tools.js` 工具与缓存）                                       |
| 改运行日志的采集 / 落盘 / 清理           | `mcp/RunLogAccess.kt`（抓点、大小上限、sidecar、历史扫描）+ `mcp/RunLogModels.kt`（键 / 文件名 / JSON）                                                                                             |
| 改配置名的解析规则                       | `mcp/RunConfigNaming.kt` + `RunLogAndNamingTest`                                                                                                                                                    |
| 改 `inspect` 返回的字段                  | `mcp/RunConfigDefinitionModels.kt` + `RunConfigDefinitionSchemas.kt` + `RunLogAndNamingTest`                                                                                                        |
| 改 Remote Process 的部署 / 启动          | `mcp/RemoteProcessRunProfileState.kt` + `mcp/RemoteProcessSync.kt` + `mcp/RemoteSshClient.kt`                                                                                                      |
| 改 `deploy_remote_process` / `list_remote_processes` | `mcp/RemoteProcessDeploySchemas.kt` + `mcp/RemoteProcessToolsProvider.kt`                                                                                                                  |
| 改 Remote Process 配置页                 | `mcp/RemoteProcessRunConfiguration.kt`（`SettingsEditor`）                                                                                                                                          |
| 改 `sync_deployments`                    | `mcp/DeploymentAccess.kt` + `mcp/DeploymentModels.kt` + `mcp/DeploymentSchemas.kt`                                                                                                                  |
| 改插件显示名 / 版本号                    | `gradle.properties` 的 `pluginName` / `pluginVersion`                                                                                                                                               |

## HARD RULES

- **包名 = `com.shenyuanlaolarou.ideamcpenhance`**: 也是 `pluginGroup` / plugin id 的命名空间
- **prompt 的格式化逻辑必须放 `prompt/format/` 且是纯函数**; MCP 的 JSON 构造必须放在对应的纯数据层
  （`RunLifecycleModels.kt` / `DeploymentModels.kt` / `RemoteProcessDeploySchemas.kt` 等）
- **MCP 那半必须能独立于 MCP Server 消失**: 任何引用 `com.intellij.mcpserver.*` 的类 **只能**由 `META-INF/mcpserver.xml`
  触发加载，`plugin.xml` 主文件里不许出现
- **禁止使用 `@TestOnly` / `@Internal` 的平台 API**; 尤其禁止 `com.intellij.execution.impl.*`
  —— `PluginManager.getPlugin` / `PluginManagerCore.getPlugin` **都**是 Internal（后者还 deprecated），
  公开的 `IdeaPluginManager` 在 2026.2 / 2026.3 里都还不存在 ⇒ 这种场景只能反射调用
- **插件运行时可见的模块必须在 `plugin.xml` 里声明**: `gradle.properties` 的 `platformBundledModules` 只管**编译期**
  classpath。不声明的话运行时看不到那些类（`NoClassDefFoundError`），Plugin Verifier 也会报整包 "not found"
- **要用某个 plugin 的**实现 jar**里的类，要依赖 plugin 而不是 module**: module 依赖只给"导出的 API"
  —— 例：`com.intellij.ssh` 的 `SshConfig` 在 `intellij.platform.ssh.core`（module，能看到），
  而 `SshConnectionService` / `SessionConfig` 在 `intellij.platform.ssh` 的实现 jar 里（看不到）⇒ 依赖
  **plugin** 级。**bundled plugin 的 id 不等于目录名**：SSH 那个是 `intellij.ssh.plugin`（目录 `platform-ssh-plugin`）
- **不随 IDEA 分发的插件（Go 等）的 API 必须走反射**: config-file 的 optional 依赖只能挡住**运行时**加载那些类，
  挡不住 Plugin Verifier 的静态扫描 —— jar 里只要有类直接 import 它，就判 "binary incompatible"
- **禁止静态全局可变状态**: 不要在 `object` 里挂 `var`
- **Type 抑制**: 禁止 `as Any` / Kotlin 等价的 type erase 绕过 / 滥用 `!!`
- **日志**: 用 IntelliJ Platform 的 `thisLogger().info/warn/error()`，不要 `println` / `e.printStackTrace()`
- **MCP 工具异常必须转成 `McpToolCallResult.error(...)`**，不能抛出去（抛出去 agent 拿到的是不可读的失败）
- **Git 提交/push**: AI 禁止自动 commit/push，必须用户显式调用（受 OpenCode 全局 `git-commit-block` rule 约束）
- **发布**: AI 禁止自动 `publishPlugin`，必须用户显式调用
- **本机持久性变更**: 禁止 AI 擅自安装软件或改本机配置文件

## MCP 扩展点的真实形状（别照着记忆写）

`com.intellij.mcpServer` 是 **IDEA 2026.3 的内置插件**（安装目录从 262 的 `plugins/mcpserver` 改成
`plugins/mcpserver-plugin`，类在 `lib/intellij.mcpserver.jar`），没发布到 Marketplace，只能靠 `bundledPlugins` 引用。
下面三个扩展点与本插件相关，全部 `dynamic="true"`：

| 扩展点全名                                     | 接口                    | 工具怎么来                                                                                                                    |
|------------------------------------------------|-------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `com.intellij.mcpServer.mcpToolsProvider`      | `McpToolsProvider`      | 自己实现 `McpTool`，返回 `McpToolCallResult`（**本插件走这条**）                                                              |
| `com.intellij.mcpServer.mcpToolset`            | `McpToolset`            | **反射式**：`suspend fun` + `@McpTool(name, title)`，schema 靠 kotlinx-serialization 反射合成，返回类型必须有 `@Serializable` |
| `com.intellij.mcpServer.mcpToolFilterProvider` | `McpToolFilterProvider` | 过滤别人注册的工具                                                                                                            |

两条路的结果由 `McpToolsListProvider.getAllMcpTools()` 合并，选哪条都能被发现。**选 `McpToolsProvider`**：省掉
kotlinx-serialization 编译器插件、schema 与输出 JSON 全部显式可测、prompt 那一半保持零依赖；代价是参数自己从 `JsonObject` 解析。

其它必须知道的：

- `McpSessionHandler` 调用**任何** `McpTool` 之前都会 `withContext(McpCallAdditionalDataElement(McpCallInfo(...)))`，
  所以直接实现的工具里 `coroutineContext.projectOrNull` 同样有效（import `com.intellij.mcpserver.projectOrNull`）
- 内置 `ExecutionToolset` 只有 `get_run_configurations` 和 `execute_run_configuration`，**没有 stop / rerun / status** ——
  本插件补的就是这三个；内置 `execute_run_configuration` 走 "Run" 路径（每次无脑 spawn，不是 Rerun），返回值只有
  `output` + `fullOutputPath`，没有 PID
- PID 拿不到不是缺陷：进程树是 `IDEA → sh → pnpm → vite`，隔了好几层 —— 正确做法是用 IDE 自己的 `ProcessHandler`

## COMMANDS

```bash
./gradlew test                    # 单元测试（纯函数 + 契约）
./gradlew test --tests "*PromptFormatTest*"   # 只跑 prompt 格式契约（秒级）
./gradlew test --tests "*RunLifecycleSchemaTest*"  # 只跑 MCP 契约
./gradlew runIde                  # 启动带插件的 IDE（手动验证 UI 与 MCP 工具）
./gradlew buildPlugin             # 构建插件 zip（输出 build/distributions/）
./gradlew check                   # 单元测试 + Kover + Qodana
./gradlew verifyPlugin            # Plugin Verifier（下载目标 IDE，慢，需网络）
./gradlew patchChangelog          # CHANGELOG.md → publishPlugin 自动依赖
./gradlew publishPlugin           # 发布到 Marketplace（需 env: PUBLISH_TOKEN 等）
```

**构建缓存陷阱**：本项目 `org.gradle.caching=true`。删除源文件后如果不 `rm -rf build` 或加 `--no-build-cache`，打出的 jar
可能残留旧包的 **空目录条目**。验证产物用：

```bash
rm -rf build && ./gradlew buildPlugin --no-build-cache
unzip -l build/distributions/idea-mcp-enhance-*.zip
```

**签名/发布 env**（`local.properties` 已 gitignore）: `PUBLISH_TOKEN`、`CERTIFICATE_CHAIN`、`PRIVATE_KEY`、
`PRIVATE_KEY_PASSWORD`。 **绝对不能**进 git。

`EditorDiagnosticsTest`（以及任何 `BasePlatformTestCase`）在 **2026.2.3 上必然失败**（2026.3 尚未重新启用验证，
该测试仍带 `@Ignore`）：`Cannot create extension
(class=Z.Z.Z.Z.Z) [Plugin: com.intellij.modules.ultimate]` —— `Z.Z.Z.Z.Z` 是 `lib/product-backend.jar` 里的平台内部接口，
失败发生在打开测试项目的 post-startup 阶段。

- **与本插件无关**：上游 `syllr/copy-as-prompt` 原样指向 2026.2.3，失败完全一致
- **绕不开**：2026.2 已停发 `ideaIC`，只能拉 Ultimate；去掉 `com.intellij.java`、加
  `idea.force.use.core.classloader=true` 都无效
- 因此该测试类打了 `@Ignore`（**原因写在注解里**），JetBrains 修复后删掉注解即可恢复

期间用 `./gradlew runIde` 手工验证平台行为（菜单项 enabled、诊断采集、MCP 工具实际启停）。

## UNIQUE STYLES / 约定

- **`BasePlatformTestCase` 的测试方法必须以 `test` 开头**（不是 `@Test`）：它继承 JUnit 3 的 `TestCase`，`@Test`
  注解不生效，方法名不对会被 **静默跳过**
- **输出格式的结尾永远是 `TRAILING_BLANK`（`\n` + 两个空格）**: 见 `prompt/format/PromptFormat.kt` 的
  KDoc，不要"顺手清理尾随空格"
- **MCP 返回的 JSON 保留 null 键而不是省略**: agent 要能区分"键不存在"（工具出错）和"值为 null"（进程还没起来）
- **诊断只取 ERROR / WARNING**: `HighlightSeverity >= WARNING`
- **路径优先相对项目根**: `promptFilePath()` 能算相对路径就用相对路径
- 剪贴板只走 `Toolkit.getDefaultToolkit().systemClipboard`
- Action 文案硬编码在 `plugin.xml`（无 i18n bundle）
- **`stopDescriptors` 会停掉该配置名的所有实例**，不是只停最新的——这正是它相对 `pkill` 的价值所在

## 注意事项（容易踩的坑）

写代码时必须守的规则，每条一行；**机制细节在 [DESIGN.md 附录 B](./DESIGN.md#附录-b平台细节备忘)**，要改行为前先读那里。

- **日志只能在 `processStarting` 时接** —— 挂到 `processStarted` 就可能漏掉启动横幅
- **日志文件名里的键 = `sha256(项目路径 + "\0" + 配置名)` 前 16 位** —— 别用官方的 `sanitizeFileName`，它会把 `console-web` 与 `console_web` 抹成同一个名字
- **历史实例按开始时间倒序，身份就是日志文件路径** —— 平台关掉 Run 标签页就什么都不剩，不靠任何内存表
- **`.log` 是本体，`.json` sidecar 只补 `pid` / `exitCode` / `endedAt`** —— sidecar 丢了安静降级，不发 warning
- **终端形态的 Shell（`EXECUTE_IN_TERMINAL=true`）是运行态工具的盲区**（没有 `ProcessHandler` ⇒ 没日志、停不掉、list 看不见）—— `inspect` 对它发 warning
- **类型插件没装 ≠ 配置不可读** —— 以 `.run.xml` 里的 `type` 为准并给 `typeKnown: false`，`inspect` 照常工作
- **定义态工具只读** —— 配置的新增 / 修改 / 删除回 IDE 界面；可重复的流程走 job skill（opencode-idea 侧 `run-job-manager`），不由 agent 代写 `.run/` 下的 xml
- **取进程号别用 `getNativePid()`**（`@ApiStatus.Experimental`）—— 用 `BaseProcessHandler.getProcess().pid()`
- **MCP Server 只认 header `IJ_MCP_SERVER_PROJECT_PATH` 或入参 `projectPath` 决定项目** —— 每个工具的 schema 都要声明 `projectPath`（可空、不进 `required`）
- **单次工具调用能否超长，取决于客户端有没有挂 `GET /stream` 的 SSE 流** —— 挂了流（OpenCode 这类标准 streamable-http 客户端）会话一直活着，实测 300 秒调用正常返回；只发 POST 不挂流会被 IDE 当 pending transport，`PENDING_TRANSPORT_TIMEOUT = 15s` 后清掉会话（日志 `Pending StreamableHttp transport timed out`），工具算完也送不回，客户端拿到空 404
- **`rerun` 的三道前置判定别删**：中间态拒绝 / 旧实例没停干净不启动 / 平台启动门
- **job / server 由配置自己在 `<envs>` 里声明**（缺省 job；`IDEA_MCP_ENHANCE_SERVER_RUN_CONFIG=true` 表示 server，变量名与缺省一致，不用在脑子里取反）：平台没有任何 API 能区分二者。**别往 `.run.xml` 塞自定义 `<option>`** —— `RunnerAndConfigurationSettingsImpl.writeScheme()` 每次从 `Element("configuration")` 重拼，未知标签会在用户下次保存该配置时静默消失；`<envs>` 是配置类型自己序列化的字段，不会丢
- **`list_running_configurations` 只报活会话，Before launch 跑完就消失** —— 看不到构建 ≠ 没执行：父配置在跑就说明前置步骤早跑完了。所以列表条目带 `beforeRunTasks[].lastRun`（含退出码与日志路径）与 `beforeLaunchOf`，文本摘要也点明关系
- **`RemoteProcess` 恒为 server** —— 它的 Run 会话跟随远端日志、永不终止，等退出就是等死（内置 `execute_run_configuration` 正是因此挂死）
- **启动 Remote Process 时临时打开 `autoReplace`** —— 否则远端已有同名进程会走 `Messages.showYesNoDialog`（`invokeAndWait` 模态框），无头场景没人点，调用一直挂着
- **`ProcessAdapter` 在 2026.2 已废弃** —— 直接实现 `ProcessListener`，只覆写 `onTextAvailable`
- **错误结果里没有 `structuredContent`**（只有 `isError == false` 且注册表开关打开才有）⇒ 错误的关键信息一律写进 text
- **沙箱里的 `config/disabled_plugins.txt` 基本都是陈旧残留** —— 插件加载失败先看它
- **`pluginUntilBuild=263.*`** —— 目标就是 263；`McpToolDescriptor` / `McpToolCategory` 的构造器签名与 262 不同，
  一个 jar 不能同时跑 2026.2 和 2026.3，别再改回 262
- **`McpToolDescriptor` 的第 4 个参数是 `displayDescription`（2026.3 新增）** —— 只给 IDE 设置页的人看，
  模型拿到的仍是 `description`；传 null 时回退到 `description`
- **`McpToolCategory` 的 `displayName` / `displayDescription`（2026.3 新增）** —— 都有默认值，按需补
- **`McpToolsProvider.getTools()` 在 Kotlin 里是函数不是属性** —— 调用写 `.getTools()`
- **平台可能为「这个配置能不能跑」弹模态框，永久卡死 EDT** ⇒ 工具侧必须主动判断「现在是不是有模态对话框在等用户」
- **`rerun` 的"结果未知"**：`started` / `runningAfter` 为 `null`（不知道）—— 别把 `null` 和 `false` 混起来。
  server 的**正常**返回就是 `triggered = true` + `started = null`，这不是异常
- **`stop` 必须处理「启动在飞」**（`rerun` 异步提交后进程可能还没挂上）—— 报「没在跑」是假话
- **克隆 = 读源文件改名** —— 只换 `<configuration>` 里的 `name`，不动 `run_configuration_name`；源不在 `.run/` 必须拒绝
- **Marketplace 新插件 id 的首个版本必须网页手动上传**，之后 `publishPlugin` 才能推新版本
- **`webDeployment` 是产品内部实现，不是平台 API**（只在 Ultimate 有）⇒ 引用它的类只能在确认插件存在后才加载（链式 optional 依赖把守）
- **下载方向默认 `AlwaysAsk` 会弹框** —— 必须显式传 `AlwaysOverwrite` / `AlwaysSkip`
- **建连接前先 `hasCredentials()` 预检** —— 否则认证失败会弹模态登录框
- **`get_project_env` 不是模型工具** —— 它是 `opencode-idea` 的 `shell.hook("create.before")` 的数据通道，在那个插件的隐藏清单里；`project_sdk_terminal` 与本地 `terminal_read` / `terminal_interrupt` / `list_terminals` 分支已经整体删除
- **`PATH` 只含 5 个 provider 的 bin，不含 IDE 进程的 `PATH`** —— 调用方负责前置拼接；如果这里把系统 PATH 也带上，OpenCode 进程的 PATH 会被整条顶掉
- **provider 的顺序由 `ProjectEnvProvider` 枚举决定，不能用注册顺序** —— `ProjectActivity` 的启动顺序不保证，直接遍历注册表会让 PATH 每次启动都不同
- **env 拉取是 `/open-in-ide` 的硬条件** —— 没拉到就报 `env-unready`，自愈路径也不清 `needsRebuild`；否则会话会停在"工具有、环境是空的"中间态
- **远程执行已整体移出本插件** —— `execute_in_remote_host` / `terminal_read` / `terminal_interrupt` / `list_terminals` / `RemoteTerminalAccess` / `terminal-io.xml` / `remote-terminal.xml` 全部删除；`opencode-idea` 的 `remote_exec` 直接 SSH，凭据由本插件的隐藏工具 `get_ssh_servers` 提供
- **`get_ssh_servers` 只返回 SSH 配置与凭据，不含 deployment 映射** —— `Deployable.copyToCredentials()` 会把 `Credentials.getPasswordAsString()` 塞进 holder（PASSWORD → `password`，KEY_PAIR → `passphrase`），所以不需要读 PasswordSafe / Keychain；工具在 `opencode-idea` 的隐藏清单里，summary 里**绝不能出现凭据字段**
- **mapping 的 `deployPath` 必须拼上 `rootFolder` 再返回** —— IDE 里它是**相对路径**（`/` = 服务器根），直接当目标目录会把文件传到远端 `/`；`runIde` 沙箱实测过 `mcp-test-project` 这一条（`rootFolder=/Users/yutao/Desktop/data/test` + `deploy=/`）

## REFERENCES

- 官方开发指南: https://plugins.jetbrains.com/docs/intellij/
- **核对平台 API 签名最可靠的来源**（本机已缓存）：
  ```bash
  D=~/.gradle/caches/9.3.1/transforms/*/transformed/idea-2026.3
  javap -p -cp "$D/lib/intellij.platform.execution.jar" com.intellij.execution.ui.RunContentDescriptor
  ```
- **反编译内置 MCP Server**（本机已安装的 2026.3）：
  ```bash
  M="$HOME/Applications/IntelliJ IDEA.app/Contents/plugins/mcpserver-plugin/lib/intellij.mcpserver.jar"
  unzip -p "$M" META-INF/plugin.xml          # 扩展点清单
  javap -p -cp "$M" com.intellij.mcpserver.McpTool
  javap -v -cp "$M" com.intellij.mcpserver.impl.ReflectionCallableMcpTool  # 看工具是怎么被收集的
  ```
- 平台源码: `~/.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/idea/<版本>/*-sources.jar`
- 本仓库: https://github.com/syllr/idea-mcp-enhance
- 上游 prompt 实现: https://github.com/syllr/copy-as-prompt
