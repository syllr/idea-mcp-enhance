# AGENTS.md

JetBrains IDE 插件（Kotlin）：把 IDE 里的信息变成 **prompt 文本**复制到剪贴板，同时给 IDE 内置的 MCP Server 补上 Run Configuration 的生命周期能力（列出 / 查询 / 停止 / 重启）。

prompt 那一半不联网、不起进程、不读配置文件；MCP 那一半只在用户显式启用 MCP Server 并调用工具时才动进程。

## 项目级元文档

| 文档                         | 关注                                 | 何时读                                    |
| ---------------------------- | ------------------------------------ | ----------------------------------------- |
| **[AGENTS.md](./AGENTS.md)** | 怎么开发（流程规则、强制约束、踩坑） | **第一份必读** — 任何任务开始前           |
| **[SPEC.md](./SPEC.md)**     | 必须满足什么（输出契约、硬规则）     | 改输出格式 / 改 MCP 返回 / Code Review 前 |
| **[DESIGN.md](./DESIGN.md)** | 怎么实现（分层、关键决策、平台坑）   | 加 action / 加工具 / 排查平台 API 问题时  |

## STACK

- Gradle 9.3.1 · JDK 21 · Kotlin 2.3.20 · IntelliJ Platform Gradle Plugin **2.19.0**
- 目标平台: **2026.2.3**（`pluginSinceBuild=262`，`pluginUntilBuild` 留空）
- **必须用 2.19.0，不能退回 2.14.0**：2.14.0 解析不了 2026.2 发行版 `modules/module-descriptors.jar` 里的 `$legacy_jps_library` 描述符，会报 `Field 'name' is required ... ModuleDescriptor`，连 `:test` 的依赖都算不出来。
- **IDEA Community 在 2026.2 已停发**（`ideaIC:2026.2.3` 不存在），只能用 `intellijIdea(...)` 拉 Ultimate。
- **运行时依赖: 无第三方库** — 只用 IntelliJ Platform API + 内置 `com.intellij.mcpServer` 提供的类型 + `kotlinx-serialization-json`（平台自带）
- 测试依赖: JUnit 4.13.2、opentest4j 1.3.0、IntelliJ Platform TestFramework
- 静态分析: Qodana（linter `jetbrains/qodana-jvm-community:2024.3`，Gradle plugin `2025.3.1`）+ Kover 0.9.5 + CodeCov
- `CHANGELOG.md` 由 `org.jetbrains.changelog` Gradle 插件（`patchChangelog` 任务）管理，**不要手改版本段落结构**

### 五个容易混淆的"名字"

| 位置                                     | 值                                    | 作用                                                         |
| ---------------------------------------- | ------------------------------------- | ------------------------------------------------------------ |
| `plugin.xml` `<id>`                      | `com.shenyuanlaolarou.ideamcpenhance` | 插件 id（市场唯一标识）。**发布后永久不可改**                |
| `gradle.properties` `pluginName`         | `IDE MCP Enhance`                     | 构建时 patch 到 `plugin.xml` `<name>` 的**显示名**           |
| `gradle.properties` `platformPlugins`    | `com.intellij.mcpServer`              | 内置 MCP Server 插件。**ID 大小写敏感，S 是大写**            |
| `settings.gradle.kts` `rootProject.name` | `idea-mcp-enhance`                    | 决定产物名 `build/distributions/idea-mcp-enhance-<版本>.zip` |
| Kotlin 包名                              | `com.shenyuanlaolarou.ideamcpenhance` | 与插件 id 保持一致                                           |

> `platformBundledPlugins` 里写成 `com.intellij.mcpserver`（小写 s）会报
> `Could not find bundled plugin with ID: 'com.intellij.mcpserver'`——Gradle 插件按**精确大小写**匹配。

## STRUCTURE

源码根: `src/main/kotlin/com/shenyuanlaolarou/ideamcpenhance/`

**两个互不依赖的能力包**，各自三层：

```
├── prompt/                        ← 能力一：复制 prompt（与上游 copy-as-prompt 逐字节一致）
│   ├── format/PromptFormat.kt     纯函数：输出格式的唯一实现处
│   └── actions/                   平台接线：取 IDE 数据、判定 enabled、写剪贴板
│       ├── CopyAsPromptAction.kt
│       ├── CopyDiagnosticsAction.kt
│       └── EditorPromptContext.kt
└── mcp/                           ← 能力二：Run Lifecycle MCP 工具
    ├── RunLifecycleModels.kt      纯数据 + 纯函数：状态快照 → JSON
    ├── RunLifecycleSchemas.kt     工具描述符 / 输入 schema / 参数解析
    ├── RunConfigAccess.kt         平台接线：RunManager / RunContentManager / ProcessHandler
    └── RunLifecycleToolsProvider.kt  三个 McpTool + 异常兜底
```

| 层                                       | 允许                                               | 禁止                      | 测试方式                                            |
| ---------------------------------------- | -------------------------------------------------- | ------------------------- | --------------------------------------------------- |
| `prompt/format/`                         | 字符串操作                                         | 平台 import、IO、环境变量 | 纯 JUnit 4（秒级）                                  |
| `mcp/RunLifecycleModels` / `Schemas`     | 字符串、JsonObject、mcpserver 的 descriptor 类型   | 平台 import、IO           | 纯 JUnit 4（秒级）                                  |
| `prompt/actions/`、`mcp/RunConfigAccess` | 平台 API                                           | 字符串格式化 / 手写 JSON  | `BasePlatformTestCase`（见「已知阻塞」）或 `runIde` |
| `mcp/RunLifecycleToolsProvider`          | 编排：解析参数 → 调接线层 → 包 `McpToolCallResult` | 直接操作 `ProcessHandler` | 描述符 + 参数解析有单测                             |

测试根: `src/test/kotlin/com/shenyuanlaolarou/ideamcpenhance/`

| 测试                                   | 类型                   | 覆盖                                                                                        | 状态            |
| -------------------------------------- | ---------------------- | ------------------------------------------------------------------------------------------- | --------------- |
| `prompt/format/PromptFormatTest`       | 纯 JUnit 4（11 例）    | 两个 prompt 格式契约 + 稳定排序                                                             | ✅ 通过         |
| `mcp/RunLifecycleSchemaTest`           | 纯 JUnit 4（29 例）    | 工具集合 / 描述符 / 输入 schema / 输出 schema / 与内置工具的逐字对齐 / 参数解析 / 输出 JSON | ✅ 通过         |
| `prompt/actions/EditorDiagnosticsTest` | `BasePlatformTestCase` | 诊断采集能从 markup model 捞出编译错误                                                      | ⛔ 禁用（见下） |

## WHERE TO LOOK

| 想做的事                                 | 改哪里                                                                                                                                                                                                     |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 改 prompt 输出格式                       | `prompt/format/PromptFormat.kt` + 同步 `PromptFormatTest` + `SPEC.md §1/§2`                                                                                                                                |
| 改菜单项文案 / 出现位置                  | `src/main/resources/META-INF/plugin.xml` 的 `<action>` / `<add-to-group>`                                                                                                                                  |
| 改 action 的启用条件                     | 各 action 的 `update()`                                                                                                                                                                                    |
| 改文件路径写法（相对/绝对）              | `prompt/actions/EditorPromptContext.kt` 的 `promptFilePath()`                                                                                                                                              |
| 改诊断采集范围 / 过滤规则                | `prompt/actions/EditorPromptContext.kt` 的 `collectDiagnostics()`（先读 `DESIGN.md §3.1`）                                                                                                                 |
| 改 MCP 工具的名字 / 描述 / 入参          | `mcp/RunLifecycleSchemas.kt` 的三个 `*Descriptor` + `RunLifecycleSchemaTest`                                                                                                                               |
| 改 MCP 工具返回的 JSON 字段              | `mcp/RunLifecycleModels.kt` 的 `toJson()`                                                                                                                                                                  |
| 改 MCP 的停止实现                        | `mcp/RunConfigAccess.kt` 的 `stopHandler` / `stopDescriptors`                                                                                                                                              |
| 改 MCP 的启动 / 重启实现                 | `mcp/RunConfigAccess.kt` 的 `startRunAndAwait`（`ProgramRunner.Callback` + 输出采集）                                                                                                                      |
| 改启动后回传的日志 / 状态流转            | `mcp/RunConfigAccess.kt` 的 `RunOutputCollector` + `mcp/RunLifecycleModels.kt` 的 `RerunReport`                                                                                                            |
| 改 rerun / stop 的可重入语义             | `mcp/RunLifecycleModels.kt` 的 `rerunBlockedReason` / `startBlockedReason` + `RunConfigAccess.kt` 的 `hasPendingRunContent`（状态判定层）+ 平台启动门（`ExecutionManager.isStarting(environment)`） |
| 改 `projectPath` / `outputSchema` 的对齐 | `mcp/RunLifecycleSchemas.kt` 的 `PROJECT_PATH_DESCRIPTION` + 三个 `*Descriptor` 的第 7 个参数                                                                                                              |
| 加一个新 MCP 工具                        | `mcp/RunLifecycleToolsProvider.kt` 加一个 `BaseRunTool` 子类 + 在 `getTools()` 里注册                                                                                                                      |
| 改插件显示名 / 版本号                    | `gradle.properties` 的 `pluginName` / `pluginVersion`                                                                                                                                                      |

## HARD RULES

- **包名 = `com.shenyuanlaolarou.ideamcpenhance`**: 也是 `pluginGroup` / plugin id 的命名空间
- **prompt 的格式化逻辑必须放 `prompt/format/` 且是纯函数**; MCP 的 JSON 构造必须放 `RunLifecycleModels.kt`
- **MCP 那半必须能独立于 MCP Server 消失**: 任何引用 `com.intellij.mcpserver.*` 的类**只能**由 `META-INF/mcpserver.xml` 触发加载，`plugin.xml` 主文件里不许出现
- **禁止使用 `@TestOnly` / `@Internal` 的平台 API**; 尤其禁止 `com.intellij.execution.impl.*`
- **禁止静态全局可变状态**: 不要在 `object` 里挂 `var`
- **Type 抑制**: 禁止 `as Any` / Kotlin 等价的 type erase 绕过 / 滥用 `!!`
- **日志**: 用 IntelliJ Platform 的 `thisLogger().info/warn/error()`，不要 `println` / `e.printStackTrace()`
- **MCP 工具异常必须转成 `McpToolCallResult.error(...)`**，不能抛出去（抛出去 agent 拿到的是不可读的失败）
- **Git 提交/push**: AI 禁止自动 commit/push，必须用户显式调用（受 OpenCode 全局 `git-commit-block` rule 约束）
- **发布**: AI 禁止自动 `publishPlugin`，必须用户显式调用
- **本机持久性变更**: 禁止 AI 擅自安装软件或改本机配置文件

## MCP 扩展点的真实形状（别照着记忆写）

`com.intellij.mcpServer` 是 **IDEA 2026.2 的内置插件**，没发布到 Marketplace，只能靠 `bundledPlugins` 引用。它的公开扩展点有三个，全部 `dynamic="true"`：

| 扩展点全名                                     | 接口                    | 工具怎么来                                                                                                                    |
| ---------------------------------------------- | ----------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `com.intellij.mcpServer.mcpToolsProvider`      | `McpToolsProvider`      | 自己实现 `McpTool`，返回 `McpToolCallResult`（**本插件走这条**）                                                              |
| `com.intellij.mcpServer.mcpToolset`            | `McpToolset`            | **反射式**：`suspend fun` + `@McpTool(name, title)`，schema 靠 kotlinx-serialization 反射合成，返回类型必须有 `@Serializable` |
| `com.intellij.mcpServer.mcpToolFilterProvider` | `McpToolFilterProvider` | 过滤别人注册的工具                                                                                                            |

两条路的结果由 `McpToolsListProvider.getAllMcpTools()` 合并，所以选哪条都能被 MCP Server 发现。

**为什么选 `McpToolsProvider`**：省掉 kotlinx-serialization 编译器插件（不用给 `plugin.xml` 额外挂依赖）、schema 与输出 JSON 全部显式可测、prompt 那一半保持零依赖。代价是参数要自己从 `JsonObject` 解析。

其它必须知道的：

- `McpSessionHandler` 在调用**任何** `McpTool` 之前都会 `withContext(McpCallAdditionalDataElement(McpCallInfo(...)))`，所以直接实现的工具里 `coroutineContext.projectOrNull` 同样有效（`McpCallInfoKt` 的顶层扩展属性，import `com.intellij.mcpserver.projectOrNull`）
- 内置的 `ExecutionToolset` 只有 `get_run_configurations` 和 `execute_run_configuration`，**没有 stop / rerun / status**——本插件补的就是这三个
- 内置 `execute_run_configuration` 走的是 "Run" 路径，每次调用无脑 spawn，**不是 Rerun**；返回值只有 `output` + `fullOutputPath`，没有 PID
- PID 拿不到不是缺陷：进程树是 `IDEA → sh → pnpm → vite`，隔了好几层。正确做法是用 IDE 自己的 `ProcessHandler`

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

**构建缓存陷阱**：本项目 `org.gradle.caching=true`。删除源文件后如果不 `rm -rf build` 或加 `--no-build-cache`，打出的 jar 可能残留旧包的**空目录条目**。验证产物用：

```bash
rm -rf build && ./gradlew buildPlugin --no-build-cache
unzip -l build/distributions/idea-mcp-enhance-*.zip
```

**签名/发布 env**（`local.properties` 已 gitignore）: `PUBLISH_TOKEN`、`CERTIFICATE_CHAIN`、`PRIVATE_KEY`、`PRIVATE_KEY_PASSWORD`。**绝对不能**进 git。

## 已知阻塞：平台集成测试跑不起来

`EditorDiagnosticsTest`（以及任何 `BasePlatformTestCase`）在 **2026.2.3 上必然失败**，报错：

```
Cannot create extension (class=Z.Z.Z.Z.Z) [Plugin: com.intellij.modules.ultimate]
  → Cannot find suitable constructor for class Z.Z.Z.Z.Z,
    expected (), (CoroutineScope), (Application), or (Application, CoroutineScope)
```

- `Z.Z.Z.Z.Z` 是 `lib/product-backend.jar` 里的平台内部（混淆）接口，失败发生在打开测试项目的 post-startup 阶段
- **与本插件无关**：把上游 `syllr/copy-as-prompt` 原样指向 2026.2.3，失败完全一致
- **绕不开**：2026.2 已停发 `ideaIC`，只能拉 Ultimate；去掉 `com.intellij.java`、加 `idea.force.use.core.classloader=true` 都无效
- 因此该测试类打了 `@Ignore`，**原因写在注解里**。JetBrains 修复后删掉注解即可恢复

期间请用 `./gradlew runIde` 手工验证平台行为（菜单项 enabled、诊断采集、MCP 工具实际启停）。

## UNIQUE STYLES / 约定

- **`BasePlatformTestCase` 的测试方法必须以 `test` 开头**（不是 `@Test`）：它继承 JUnit 3 的 `TestCase`，`@Test` 注解不生效，方法名不对会被**静默跳过**
- **输出格式的结尾永远是 `TRAILING_BLANK`（`\n` + 两个空格）**: 见 `prompt/format/PromptFormat.kt` 的 KDoc，不要"顺手清理尾随空格"
- **MCP 返回的 JSON 保留 null 键而不是省略**: agent 要能区分"键不存在"（工具出错）和"值为 null"（进程还没起来）
- **诊断只取 ERROR / WARNING**: `HighlightSeverity >= WARNING`
- **路径优先相对项目根**: `promptFilePath()` 能算相对路径就用相对路径
- 剪贴板只走 `Toolkit.getDefaultToolkit().systemClipboard`
- Action 文案硬编码在 `plugin.xml`（无 i18n bundle）
- **`stopDescriptors` 会停掉该配置名的所有实例**，不是只停最新的——这正是它相对 `pkill` 的价值所在

## 注意事项（容易踩的坑）

- **2026.1 → 2026.2 的 API 变化**：`RunContentDescriptor` **没有** `getRunProfile()` 了；改用 `getRunConfigurationName()` / `getRunConfigurationTypeId()`。`getExecutors(descriptor)` 在 `ExecutionManager` 上是 `@ApiStatus.Internal`，本插件不用它。
- **`ProcessHandler.getNativePid()` 返回 `CompletableFuture`**: 进程没创建出来时 future 未完成，`get()` 会阻塞。只取 `isDone` 为 true 的结果。
- **平台没有 `ExecutionManager.stopRunProfile`**: Stop 按钮走 `ExecutionManagerImpl.stopProcess`（`impl` 包）。本插件用等价的公开 API 组合重写（`TERMINATION_REQUESTED` + `killProcess`/`detachProcess`/`destroyProcess`），语义逐条对齐，见 `RunConfigAccess.stopHandler` 的 KDoc
- **`McpToolSchema.propertiesSchema` 只装 properties**，不含根级 `"type": "object"`；根类型由 MCP Server 合成 schema 时补（input 与 output 都一样）
- **MCP Server 的项目解析只认两样东西**：header `IJ_MCP_SERVER_PROJECT_PATH`，或调用参数 `projectPath`。**"只打开了一个项目"不算**——实测不带参数调内置 `get_run_configurations` 同样被拒（`Unable to determine the target project`）。因此三个工具的 schema 必须声明 `projectPath`（可为空、不进 `required`），否则没配 header 的客户端一个工具都用不了
- **服务器会按客户端配置裁剪 schema**：客户端给了 header 时，它会把**所有**工具的 `projectPath` 参数从 schema 里摘掉（官方工具也一样）；只在没给 header 时才保留。所以验"有没有声明 projectPath"必须用**不带 header**的 `tools/list`，否则会误判成"没声明"
- **启动 Run Configuration 是异步的**：`ExecutionManager.restartRunProfile(env)` 发射后不管，紧接着做快照必然读到 `runningAfter = 0`、`instances[]` 里只有旧的已死实例。必须走 `ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)` + `ProgramRunner.Callback`，在 `processStarted` 里拿到 `descriptor` 才算"起来了"（内置 `execute_run_configuration` 也是这么做的）
- **`executeConfigurationAsync` 的第一句就是平台自己的门**：`if (!manager.isStarting(environment)) { … }` —— 同配置 + 同 executor + 同 runner 正在启动时，这次提交被**静默丢弃**（回调都不装，既不回 `processStarted` 也不回 `processNotStarted`）。`isStarting` 读的是 `inProgress`，而 `inProgress` 是 `executeConfiguration` 一进来就加、**进程创建并 `startNotify` 之后才减**（`ExecutionManagerImpl.kt` 里 `inProgress.update { it - inProgressEntry }` 紧跟 `processHandler.startNotify()`）⇒ **碰撞窗口 = 整个启动过程**，**包含 before-launch 的构建时长**（实测：给配置挂一个永不退出的 before-launch 后，第二次 rerun 0 秒返回 "another start is already in progress"；我早先说"窗口只有进程创建那几十毫秒"是错的）。**平台永远不会起两个实例**，所以"防双实例"不需要我们做；`rerun` 只需在发起前用同一个条件问一次，把"静默丢弃"换成立刻、精确的错误
- **单次工具调用的时长必须远小于 MCP Server 的会话耐心**（实测约 **15 秒**：`Pending StreamableHttp transport timed out` → `Removing session`）。请求悬着超过它，会话会被移除，工具算完也送不回结果 → 客户端拿到 **HTTP 404 空响应**。实测边界：8 秒能送达、20 秒不能；**官方工具也一样**（30 秒的 `execute_run_configuration` 实测 30.1 秒后空 404），而它的 `timeout` 默认就是 10000。所以 `startTimeoutMs` 默认取 10000（**与原生 `execute_run_configuration` 的 `timeout` 默认值一致**）。注意 `rerun` 的总时长是三段相加（stop + start + 输出窗口），"慢停 + 慢启"会超过上限——**凡是给工具加"等待"的地方，都要把总时长和这个上限一起算**
- **before-launch 步骤可以查出来**：`RunConfiguration.getBeforeRunTasks()`（公开默认方法）。平台在它们跑完之前**不产出 Run 内容**（实测：给 `node-server` 挂一个永不退出的 before-launch 后，`list` 里只有那个 before-launch 配置有实例、`node-server` 什么都没有）。所以启动超时的文案必须点明这一点，而不是让人以为启动失败
- **`rerun` 的三道前置判定互补，别删其中任一道**：① 中间态拒绝（含"Run 内容已出现、进程还没挂上"这一档；覆盖用户手动点按钮）② 旧实例没停干净就不启动 ③ 平台启动门（`isStarting(environment)`，把"静默丢弃"换成 0 秒精确错误）。**都不依赖本插件自建的内存状态**
- **`ProcessAdapter` 在 2026.2 已废弃**：`ProcessListener` 的方法现在全都有默认实现，直接实现接口、只覆写 `onTextAvailable` 即可
- **错误结果里没有 `structuredContent`**：`McpSessionHandlerKt.toSdkToolCallResult` 的字节码是
  `getStructuredToolOutputEnabled() ifeq … ; isError() ifne …` —— 即只有"注册表开关 `mcp.server.structured.tool.output` 打开**且** `isError == false`"才会输出结构化内容。所以给 `McpToolCallResult.error(text, json)` 传的第二个参数在 wire 上**看不到**：**错误文案必须自洽**（关键信息一律写进 text）
- **中间态判定真的能抓到**（实测，不是推理）：`stop` 等待进程退出的窗口里，另一会话 rerun 会拿到
  `isError=true` + `"…is currently stopping, and the IDE disables Rerun in that state."`。判据与 GUI 同源：
  平台的 `FakeRerunAction.isEnabled` 判 `isStarting()` / `isProcessTerminating()`，我们按**每个实例**读
  `ProcessHandler` 的同一对状态（`startNotified` / `isProcessTerminating`），粒度更细、且不依赖 UI 组件树
- **沙箱里出现 `config/disabled_plugins.txt` 基本都是陈旧残留**：该文件由 Gradle 插件的 `PrepareSandboxTask` 从 `IntelliJPlatformPluginsExtension.disablePlugins` 生成，而本仓库的 `build.gradle.kts` / `gradle.properties` 都没配它（`config` 与 `config-test` 两份沙箱里它都是 0 字节）。一旦里面有 `com.intellij.modules.ultimate`，Node.js / Go / Python / Spring 等一大批插件会全部加载失败（日志里是 `requires plugin with id=com.intellij.modules.ultimate to be enabled`）
- **`McpToolsProvider.getTools()` 在 Kotlin 里是函数不是属性**（覆写 `getXxx()` 不会变成 `val`），调用要写 `.getTools()`
- **`HighlightInfo` 在 `...daemon.impl` 包里，但只是 `@ApiStatus.NonExtendable`**，可以用
- **daemon 高亮存在 document 级 `DocumentMarkupModel`**，不是 `editor.markupModel`。读错地方会得到空列表（不报错，静默失败）
- **平台会为了"这个配置能不能跑"弹模态对话框，那会永久卡死 EDT**（实测用 `jstack` 抓到：`ExecutionManagerImpl.executeConfiguration` → `RunDialog.editConfiguration` → `DialogWrapper.show`）。触发条件是 `RunManagerImpl.canRunConfiguration(environment)` 为 false，即 `settings.checkSettings(executor)` 抛 `IndexNotReadyException`（IDE 还在建索引，刚打开大项目时很常见）或 `RuntimeConfigurationError`（配置本身有致命问题）。无头场景没人点那个对话框 ⇒ EDT 被占住 ⇒ 我们 `withContext(Dispatchers.EDT)` 的提交再也回不来，请求一直悬着、会话被移除、调用方拿到空 404。**所以 `startRunAndAwait` 必须先用同一个公开方法 `RunnerAndConfigurationSettings.checkSettings(executor)` 提前问一次**，把这两种情况转成立刻、可读的结构化错误。注意 `checkSettings` **不检查 before-launch**（`checkSettingsBeforeRun()` 默认空实现），但**这不是缺陷**：before-launch 配错了是使用姿势问题，执行时就会暴露、用户自己会去改。**工具侧 MUST 主动判断"现在是不是有模态对话框在等用户"**：扫本 JVM 的 `Window.getWindows()`，找可见且带模态语义的 `Dialog`（`isModal || modalityType != MODELESS`），命中就立刻返回"这需要用户去 IDE 处理"。**时机和顺序很关键**：这套判断（对话框 / `checkSettings` / `isStarting`）必须在**停旧实例之前**先问一次，并在**真正提交之前**再问一次——先停后判会"把在跑的应用白白杀掉，然后才告诉用户需要你去 IDE 处理"（实测过这个坏顺序：重排后同样的场景 0.02 秒返回错误，且旧实例仍然活着）。**别用 `DialogWrapper.findInstanceFromFocus()`**——它读 `KeyboardFocusManager.getFocusOwner()`，IDE 窗口没被聚焦时（agent 在跑、窗口在后台）是 null，实测判断直接落空、白等满 `startTimeoutMs`。这一层比 `checkSettings` 更通用：后者只拦"平台因为这个配置而不能跑"，前者拦"别的对话框占着 EDT"（例如用户自己开的设置窗、上一次调用留下的编辑框）。**而且 MUST NOT 为"提交到 EDT"新增超时**：它与"等进程创建"共用 `startTimeoutMs`。上限必须留着——完全不留的话工具永远返回不了任何东西，AI 拿到空 404，就没法把"请用户去 IDE 处理"转告出去，而那正是这类卡点唯一的出路（实测：用内置 `execute_run_configuration` 打一个脚本不存在的配置，平台真的会弹 "Edit Configuration" 把 EDT 占住；此时我们的 rerun 会**立刻**（实测 0.03 秒，走主动判断）返回"这需要用户到 IDE 里处理"，而 `list_running_configurations` 仍然 0.01 秒可用，所以文案里"先查状态再重试"的建议成立）
- **`rerun` 有异步模式 `waitForStart = false`**（对齐原生 `execute_run_configuration` 的 `waitForExit`）：只提交启动请求就返回，`started` / `runningAfter` 为 `null`（"不知道"），`transition[]` 以 `START_SUBMITTED` 收尾，调用方用 `list_running_configurations` 轮询。给"before-launch 比会话耐心还长"的配置用——那种情况同步等下去只会把结果丢成空 404。**别把 `null` 和 `false` 混起来**：`false` 是"失败"，会让 agent 去重试
- **`stop` 必须处理"启动在飞"**：`rerun` 异步模式（`waitForStart = false`）提交完就返回，此时 Run 内容可能还没建、进程还没挂上；这时 `stop` 直接报 "not running; nothing to stop" 是**假话**——进程随后就会起来（实测：stop 返回成功 3 秒后进程在跑、端口占着）。正确做法是先用 `hasPendingRunContent` / `ExecutionManager.isStarting(env)` 判断"有启动在飞"，再**有界等待**（`PENDING_START_ATTACH_TIMEOUT_MS`，2000ms）让它挂上进程后照常停；等不到就如实说明"启动仍在飞、什么都没停"。加上 `stop` 自己的 `waitTimeoutMs`（默认 5000），最坏总时长约 7 秒，仍在会话耐心之内
- **`create_shell_run_configuration` 直接写 `.run.xml` 文件，不调平台创建 API**：平台本来就是文件驱动的（`RunConfigurationInArbitraryFileScanner` 启动扫描 + `RCInArbitraryFileListener` 监听 `*.run.xml`）。文件形状与 IDE 自己写的字节一致（8 个业务 option + `<envs>` + `<method>`，`INDEPENDENT_*` 带 `true`——sh 插件 303 个类里无一在字节码里读写它们，是平台自管标记；属性值转义 `& < > "` + 换行 `&#10;`；相对路径转 `$PROJECT_DIR$` 宏）。**不需要 `com.jetbrains.sh` 依赖**（纯字符串生成 + `java.nio` 写文件 + `RunManager.findConfigurationByName` 轮询注册）。**目标文件已存在必须拒绝**（没有 delete 工具，不悄悄覆盖）
- **写完 `.run.xml` MUST 调 `LocalFileSystem.refreshAndFindFileByNioFile`**，别只用 `Files.writeString`：`RCInArbitraryFileListener` 只处理 `LocalFileSystem` 的 `VFileCreateEvent`，纯 nio 写的文件 VFS 注意不到就**既不触发监听器也不进配置列表**（实测：文件确实落盘，但 IDE 等满 3 秒也没注册，工具只能如实报"尚未加载"）。刷新一次就正常（实测 `canRun` 立刻变 true，`rerun` 立刻能跑）。两个入口都是公开 API，也都不在 `execution.impl` 包里
- **克隆 = 读源文件改名**：只换 `<configuration>` 标签里的 `name`，`run_configuration_name`（别人的引用）不动；源不在 `.run/` 里必须拒绝
- **Marketplace 新插件 id 的首个版本必须网页手动上传**，之后 `publishPlugin` 才能推新版本

## REFERENCES

- 官方开发指南: https://plugins.jetbrains.com/docs/intellij/
- **核对平台 API 签名最可靠的来源**（本机已缓存）：
  ```bash
  D=~/.gradle/caches/9.3.1/transforms/*/transformed/idea-2026.2.3-aarch64
  javap -p -cp "$D/lib/intellij.platform.execution.jar" com.intellij.execution.ui.RunContentDescriptor
  ```
- **反编译内置 MCP Server**（本机已安装）：
  ```bash
  M="$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/mcpserver/lib/mcpserver.jar"
  unzip -p "$M" META-INF/plugin.xml          # 扩展点清单
  javap -p -cp "$M" com.intellij.mcpserver.McpTool
  javap -v -cp "$M" com.intellij.mcpserver.impl.ReflectionCallableMcpTool  # 看工具是怎么被收集的
  ```
- 平台源码: `~/.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/idea/<版本>/*-sources.jar`
- 本仓库: https://github.com/syllr/idea-mcp-enhance
- 上游 prompt 实现: https://github.com/syllr/copy-as-prompt
