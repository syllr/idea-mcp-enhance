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
    └── RunLifecycleToolsProvider.kt  四个 McpTool + 异常兜底
```

| 层                                       | 允许                                               | 禁止                      | 测试方式                                            |
| ---------------------------------------- | -------------------------------------------------- | ------------------------- | --------------------------------------------------- |
| `prompt/format/`                         | 字符串操作                                         | 平台 import、IO、环境变量 | 纯 JUnit 4（秒级）                                  |
| `mcp/RunLifecycleModels` / `Schemas`     | 字符串、JsonObject、mcpserver 的 descriptor 类型   | 平台 import、IO           | 纯 JUnit 4（秒级）                                  |
| `prompt/actions/`、`mcp/RunConfigAccess` | 平台 API                                           | 字符串格式化 / 手写 JSON  | `BasePlatformTestCase`（见「已知阻塞」）或 `runIde` |
| `mcp/RunLifecycleToolsProvider`          | 编排：解析参数 → 调接线层 → 包 `McpToolCallResult` | 直接操作 `ProcessHandler` | 描述符 + 参数解析有单测                             |

测试根: `src/test/kotlin/com/shenyuanlaolarou/ideamcpenhance/`

| 测试                                   | 类型                   | 覆盖                                     | 状态            |
| -------------------------------------- | ---------------------- | ---------------------------------------- | --------------- |
| `prompt/format/PromptFormatTest`       | 纯 JUnit 4（11 例）    | 两个 prompt 格式契约 + 稳定排序          | ✅ 通过         |
| `mcp/RunLifecycleSchemaTest`           | 纯 JUnit 4（21 例）    | 工具集合 / 描述符 / 参数解析 / 输出 JSON | ✅ 通过         |
| `prompt/actions/EditorDiagnosticsTest` | `BasePlatformTestCase` | 诊断采集能从 markup model 捞出编译错误   | ⛔ 禁用（见下） |

## WHERE TO LOOK

| 想做的事                        | 改哪里                                                                                     |
| ------------------------------- | ------------------------------------------------------------------------------------------ |
| 改 prompt 输出格式              | `prompt/format/PromptFormat.kt` + 同步 `PromptFormatTest` + `SPEC.md §1/§2`                |
| 改菜单项文案 / 出现位置         | `src/main/resources/META-INF/plugin.xml` 的 `<action>` / `<add-to-group>`                  |
| 改 action 的启用条件            | 各 action 的 `update()`                                                                    |
| 改文件路径写法（相对/绝对）     | `prompt/actions/EditorPromptContext.kt` 的 `promptFilePath()`                              |
| 改诊断采集范围 / 过滤规则       | `prompt/actions/EditorPromptContext.kt` 的 `collectDiagnostics()`（先读 `DESIGN.md §3.1`） |
| 改 MCP 工具的名字 / 描述 / 入参 | `mcp/RunLifecycleSchemas.kt` 的四个 `*Descriptor` + `RunLifecycleSchemaTest`               |
| 改 MCP 工具返回的 JSON 字段     | `mcp/RunLifecycleModels.kt` 的 `toJson()`                                                  |
| 改 MCP 的停止 / 重启实现        | `mcp/RunConfigAccess.kt` 的 `stopHandler` / `stopDescriptors` / `restartRunProfile`        |
| 加一个新 MCP 工具               | `mcp/RunLifecycleToolsProvider.kt` 加一个 `BaseRunTool` 子类 + 在 `getTools()` 里注册      |
| 改插件显示名 / 版本号           | `gradle.properties` 的 `pluginName` / `pluginVersion`                                      |

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
- **`McpToolSchema.propertiesSchema` 只装 properties**，不含根级 `"type": "object"`；根类型由 MCP Server 合成 schema 时补
- **`McpToolsProvider.getTools()` 在 Kotlin 里是函数不是属性**（覆写 `getXxx()` 不会变成 `val`），调用要写 `.getTools()`
- **`HighlightInfo` 在 `...daemon.impl` 包里，但只是 `@ApiStatus.NonExtendable`**，可以用
- **daemon 高亮存在 document 级 `DocumentMarkupModel`**，不是 `editor.markupModel`。读错地方会得到空列表（不报错，静默失败）
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
