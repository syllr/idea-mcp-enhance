# DESIGN.md — IDE MCP Enhance 设计文档

> **本文档定位**：项目级**架构设计文档**。定义"如何实现"，不重复"必须满足什么"（那在 `SPEC.md`）。
>
> **阅读顺序**：`AGENTS.md` → `SPEC.md` → `DESIGN.md`

---

## 0. 项目概述

一个 JetBrains IDE 插件，由两个**互不依赖**的能力组成：

1. **复制 prompt**：两个编辑器右键菜单，把选区 / 当前文件诊断变成 LLM-ready 文本进剪贴板
2. **Run Lifecycle MCP 工具**：三个 MCP 工具，补齐内置 MCP Server 缺失的 Run Configuration 生命周期能力

**设计目标**（按优先级）：

1. **MCP 能力不侵入 prompt 能力** —— 没装 MCP Server 的 IDE 上，插件是一个纯粹的剪贴板工具
2. **MCP 增强不改用户的项目** —— 不包装启动命令、不按命令行匹配、不依赖固定端口
3. **对外契约可测** —— prompt 格式与 MCP 返回 JSON 都是纯函数，单测逐字段断言
4. **平台行为交给平台** —— 停止 / 重启都走 IDE 自己的 API，与界面按钮同一条代码路径

---

## 1. 分层

```
src/main/kotlin/com/shenyuanlaolarou/ideamcpenhance/
├── prompt/
│   ├── format/PromptFormat.kt          ← 纯函数：prompt 格式的唯一实现处
│   └── actions/
│       ├── CopyAsPromptAction.kt       ← 平台接线：选区
│       ├── CopyDiagnosticsAction.kt    ← 平台接线：诊断
│       └── EditorPromptContext.kt      ← 平台 API → 纯数据
└── mcp/
    ├── RunLifecycleModels.kt           ← 纯数据 + 纯函数：状态快照 → JSON
    ├── RunLifecycleSchemas.kt          ← 工具描述符 / 输入 schema / 参数解析
    ├── RunConfigAccess.kt              ← 平台 API：RunManager / RunContentManager / ProcessHandler
    └── RunLifecycleToolsProvider.kt    ← 三个 McpTool：解析参数 → 调接线层 → 包结果
```

| 层                                                            | 允许                                                 | 禁止                      |
| ------------------------------------------------------------- | ---------------------------------------------------- | ------------------------- |
| `prompt/format/`、`RunLifecycleModels`、`RunLifecycleSchemas` | 字符串 / `JsonObject` / mcpserver 的 descriptor 类型 | 平台 import、IO           |
| `prompt/actions/`、`RunConfigAccess.kt`                       | 平台 API                                             | 字符串格式化 / 手写 JSON  |
| `RunLifecycleToolsProvider.kt`                                | 编排                                                 | 直接操作 `ProcessHandler` |

**为什么这么切**：两个能力各有一样"对外契约"（prompt 文本 / MCP JSON），把契约做成纯函数，改契约的成本 = 改一个函数 + 改断言，不需要启动 IDE。反过来，平台接线集中在一层，出问题时知道去哪儿看。

---

## 2. Prompt 部分的关键决策

（与上游 `syllr/copy-as-prompt` 一致，完整推导见其 `DESIGN.md`）

| #   | 决策                                         | 一句话理由                                                                                                        |
| --- | -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| 1   | 诊断来源用 document 级 `DocumentMarkupModel` | `DaemonCodeAnalyzerImpl.getHighlights` 带 `@TestOnly`；读 `editor.markupModel` 得到空列表且**不报错**（静默失败） |
| 2   | 严重级别用 `>= WARNING` 阈值而非枚举白名单   | 一个条件同时完成"过滤非诊断高亮"和"分级映射"                                                                      |
| 3   | 诊断范围是整个文件，不跟随选区               | 少给信息的代价高于多给信息                                                                                        |
| 4   | 路径优先相对项目根                           | 不把本机目录结构带进 prompt                                                                                       |
| 5   | 代码围栏带语言标识                           | 让渲染端高亮，也让 agent 少一次猜测                                                                               |
| 6   | 剪贴板直接用 AWT，不用 `CopyPasteManager`    | 后者带平台富文本语义，粘到浏览器输入框可能带格式                                                                  |
| 7   | 不用 `object` 单例、不引入 i18n              | 两个无状态 action，文案硬编码在 `plugin.xml`                                                                      |

详见 `prompt/format/PromptFormat.kt` 与 `EditorPromptContext.kt` 的 KDoc。

---

## 3. MCP 部分：从"起两个实例"到"真正的 Rerun"

### 3.1 问题是怎么发现的

内置 MCP 的 `execute_run_configuration` 每次调用都是**无脑 spawn 一个新进程**：

```
第一次：PID 2100 占 5173
第二次：PID 2196 占 5174（日志：Port 5173 is in use, trying another one...）
```

MCP 侧完全不知道 2100 还活着——它没有状态查询，也没有停止语义。而 vite 不会因为端口被占就报错退出，它会**自动换端口**，所以肉眼极难发现多了一个实例。

想修这个，需要"停止"。而内置 MCP Server 没有。

### 3.2 被否掉的方案（都试过或分析过）

| 方案                                                  | 结论                                                                                                                                                                 |
| ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 外部 `pkill` / 按端口杀                               | ❌ IDE 不知道进程没了，Run 窗口标签页仍显示"运行中"，下次点运行会撞端口                                                                                              |
| `package.json` 里前置 `stop && start`                 | ❌ 只覆盖 Node；每个技术栈都要重写一遍；本质是**改变项目的启动方式**而不是适配 IDE                                                                                   |
| Node 启动器 `run-with-pid.mjs` 记 PID                 | ❌ PID 来自 `child_process.spawn()` 的 `child.pid`（父进程才知道），不是 IDE 给的；只能包 Node 命令；`mvn spring-boot:run` 还会再 fork 一层，`child.pid` 是 maven 的 |
| 按端口 `lsof -ti tcp:5173` 反查                       | ⚠️ 语言无关、不改项目，但**依赖应用监听固定端口**；CLI / 随机端口场景走不通                                                                                          |
| Maven 里做（`exec-maven-plugin` / `spring-boot:run`） | ❌ `spring-boot:run` 没这个选项；塞进 `pom.xml` 会让 CI 跑 `mvn package` 也被误伤                                                                                    |
| **写 IDEA 插件，走 IDE API**                          | ✅ 语言无关、不改项目、拿得到 `ProcessHandler`                                                                                                                       |

### 3.3 决策 1：停止用公开 API 重新实现，不用 `ExecutionManagerImpl`

平台的 Stop 按钮走 `ExecutionManagerImpl.stopProcess(descriptor)`。那是 `com.intellij.execution.impl` 包，`@ApiStatus.Internal`。本插件改为用**等价的公开 API 组合**（`RunConfigAccess.stopHandler`）：

```kotlin
handler.putUserData(ProcessHandler.TERMINATION_REQUESTED, true)   // ① 平台知道"用户主动停的"
if (handler is KillableProcess && handler.isProcessTerminating) handler.killProcess()  // ② 已卡住就强杀
else if (!handler.isProcessTerminated) {
    if (handler.detachIsDefault()) handler.detachProcess() else handler.destroyProcess() // ③ 与平台一致
}
```

平台实现把 ②③ 放在 `Dispatchers.Default` 上异步做；本插件同步做——`killProcess` / `destroyProcess` 都只是非阻塞的信号投递，同步调用不会卡住调用线程。

**代价**：平台改了停止语义，这里要跟着改。**收益**：不绑死在一个 `impl` 包上。

### 3.4 决策 2：实例归属靠 `getRunConfigurationName()`，不靠 `getRunProfile()`

要回答"这个配置有几个实例在跑"，就得把 `RunContentDescriptor` 关联回配置。

**踩到的坑**：2026.1 的 `RunContentDescriptor` 有 `getRunProfile()`，**2026.2 删掉了**。现在只有 `getRunConfigurationName()` / `getRunConfigurationTypeId()`——反倒是更直接的字段。

**不能用 `getDisplayName()`**：那是 UI 文本，多实例时会被加 "(2)" 之类后缀，拿它和配置名比对必然失配——而"同一配置有几个实例"恰恰是要回答的核心问题。

### 3.5 决策 3：`rerun` 自己接 `ProgramRunner.Callback` 同步等启动，而不是 `restartRunProfile` / `ExecutionUtil.restartIfActive`

`ExecutionUtil.restartIfActive(descriptor)` 看起来正合适，但它内部是：

```java
ExecutionEnvironment env = ExecutionDataKeys.EXECUTION_ENVIRONMENT.getData(
        DataManager.getInstance().getDataContext(descriptor.getComponent()));
if (env != null) restart(env);
```

**从 UI 组件的 DataContext 里捞环境**——这依赖 Run 工具窗口的组件树，在 MCP 这条无头路径上不可靠。

`ExecutionManager.restartRunProfile(ExecutionEnvironment)` 是 Rerun 按钮走的路径，但它是**发射后不管**的：调用后立刻
快照，新实例的 `RunContentDescriptor` 还没注册，`runningAfter` 必然读到 0。实测过一次：

```json
{
  "mode": "STARTED",
  "runningAfter": 0,
  "instances": [
    { "executionId": 5, "running": false },
    { "executionId": 7, "running": false }
  ]
}
```

而人话摘要写着 "Started ... — it was not running."；1 秒后再查还是空，4 秒后新实例（`executionId 8, pid 28247`）才出现。
agent 完全会把它读成"启动失败"，进而重复启动或误报。

内置 MCP 的 `execute_run_configuration` 没有这个问题：它的文档明确写
_"When `waitForExit=false`, waits only for the process to start, then returns immediately"_。
它的实现放在 `com.intellij.mcpserver.util.Execution_utilKt`，核心是
`ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)` + 一个 `ProgramRunner.Callback`：
在 `processStarted` 里拿 `descriptor.processHandler`、`addProcessListener`、`startNotify()`，
把 stdout/stderr 同时写进内存预览和 `ij_run__*.log`。

**本插件照搬这条机制，但只用公开平台 API**：

```kotlin
ExecutionEnvironmentBuilder
    .createOrNull(DefaultRunExecutor.getRunExecutorInstance(), settings)
    ?.activeTarget()      // 与界面按 Run 时选中的 target 一致，而不是硬编码某个 ExecutionTarget
    ?.build()
// → startRunAndAwait(): executeConfigurationAsync(..., callback) → 等 pid 可读 → 等第一段输出 → 等 Run 内容注册
```

**为什么不直接调官方的 `Execution_utilKt`**：它在 `com.intellij.mcpserver.util` 下，没有文档承诺，
且 `StartedRunConfigurationExecution` 是包内可见的（`final class`，非 public），
把插件链接到它等于绑死在一个随时会变的内部实现上。用公开 API 复刻能拿到完全一样的可观察行为，
代价只是多几十行代码。

**提交前必须先过三道判断**（都用公开 API，不自己造状态；第三道是兜底）。**调用时机**：`rerunNow` 在**停旧实例之前**先问一次（否则会先把在跑的应用杀掉、再告诉用户去 IDE 处理），`startRunAndAwait` 在**真正提交之前**再问一次（状态可能在停止期间变化）：

1. `settings.checkSettings(executor)` —— 平台在 `ExecutionManagerImpl.executeConfiguration` 里先问
   `RunManagerImpl.canRunConfiguration`（实现就是它），为 false 时**弹 `RunDialog.editConfiguration`
   模态对话框**。无头 / agent 场景没人点它，EDT 会被永久占住，我们那次
   `withContext(Dispatchers.EDT)` 的提交就再也回不来（`jstack` 实测抓到这个栈）。所以提前问一次，
   把 `IndexNotReadyException` / `RuntimeConfigurationError` 转成立刻、可读的结构化错误。
2. `ExecutionManager.isStarting(environment)` —— `executeConfigurationAsync` 的第一句就是它，
   命中时这次提交被**静默丢弃**（连回调都不装）。`inProgress` 从 `executeConfiguration` 一进来就加、
   进程创建并 `startNotify` 之后才减，所以**整个启动过程（含 before-launch 构建）都算"启动中"**，
   这个门能覆盖"构建中又发一次 rerun"的长窗口。

3. `DialogWrapper.findInstanceFromFocus()?.isModal` —— "现在是不是有模态对话框在等用户"。
   这是**通用**的 EDT 卡点检测（`checkSettings` 只管"这个配置能不能跑"），命中就立刻返回
   "这需要用户去 IDE 处理"。留着一层 `startTimeoutMs` 上限作为最后的网：完全不留的话工具永远
   返回不了任何东西，AI 拿到空 404，就没法把"请用户去 IDE 处理"转告出去——而那正是这类卡点唯一的出路。

**`waitForStart = false`（异步提交）**：只走第 1、2 道门 + `executeConfigurationAsync`，不等回调就返回。
它存在的唯一理由是 before-launch 比 MCP Server 的会话耐心（实测约 15 秒）还长——那种情况同步等下去，
结果必然丢成空 404。此时 `started` / `runningAfter` 必须是 `null`（"不知道"），
绝不能拿提交瞬间的快照冒充结果（那正是上面那个 P1 的病根）。

`createOrNull` 返回 null 时如实报 `mode = NO_OP` 并说明原因，而不是硬启一个大概率失败的东西。

**为什么不用 `ProcessAdapter` 挂监听**：它在 2026.2 已废弃，而 `ProcessListener` 的方法现在全都有默认实现，
直接实现接口即可（`RunOutputCollector.listener`）。

**日志文件前缀故意用 `ij_rerun__` 而不是官方的 `ij_run__`**：两者内容与生命周期语义一样，
同目录下能一眼看出哪个是本插件的 rerun 产生的。

### 3.6 决策 4：三个工具的粒度

| 工具               | 为什么需要                                                                                                                                         |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| `list_running_...` | 内置的 `get_run_configurations` 只给**静态配置信息**（名字、是否支持启动参数覆盖），不给运行状态；本插件只报在跑的，整份返回里不含任何已结束的实例 |
| `stop_...`         | 补最关键的缺口；且**停掉全部实例**——只停最新的那个等于没修端口漂移                                                                                 |
| `rerun_...`        | 改完代码重启，是本插件存在的首要理由                                                                                                               |

`stop` 幂等（对没在跑的配置返回"无需停止"而非报错），`rerun` 不幂等（每次都是新的一次启动）。

**第四个工具 `get_..._status` 被砍掉了**（曾经存在过）：它按名字查单个配置的状态，但经实测判定冗余 ——
"名字纠错"`stop` / `rerun` 本来就有（同一套 `findSettingsByName` + `unknownConfiguration`），
"看实时状态"由 `list_running_...` 覆盖（项目里并发的作业本来就不多，全量拉一次很便宜），
它剩下的唯一价值只是"省一次全量拉取"。工具少一个，agent 少一次选择。

### 3.7 决策 5：null 键保留不省略

`pid` / `exitCode` 在进程没起来时是 null。JSON 里如果省略这两个键，agent 无法区分"工具出错了所以没这个字段"和"进程还没启动所以 pid 还没有"。因此一律输出 `"pid": null`。

### 3.8 决策 6：`stop` 的等待与强杀

`ProcessHandler.waitFor(timeoutMs)` 会阻塞，所以给一个**共享的截止时间**而不是每个进程各等一轮：

```
deadline = now + waitTimeoutMs
for (handler in handlers) { handler.waitFor(剩余时间) }
超时后若 force 且仍卡在 terminating → killProcess() → 再给 1s 收尾
```

`waitTimeoutMs` 默认 5000，并且强制 ≥1ms——`waitFor(0)` 在部分 handler 上会被当成"无限等待"，那会把 MCP 调用挂死。

### 3.9 决策 7：可选依赖与类加载隔离

```xml
<depends optional="true" config-file="mcpserver.xml">com.intellij.mcpServer</depends>
```

`mcpserver.xml` 只在 MCP Server 存在时被读取，里面注册 `RunLifecycleToolsProvider`。因此所有引用 `com.intellij.mcpserver.*` 的类**只在那一刻才可能被加载**——没有 MCP Server 的 IDE 上，它们连解析都不会发生。

这是 JetBrains 插件处理可选依赖的标准姿势：**主 `plugin.xml` 里不许出现任何可选插件的类型**。

### 3.10 决策 8：为什么用 `McpToolsProvider` 而不是 `McpToolset`

见 `AGENTS.md` 的「MCP 扩展点的真实形状」。简述：`McpToolset` 那条路是反射式的，要挂 kotlinx-serialization 的编译器插件，返回类型必须 `@Serializable`；`McpToolsProvider` 让我们手写 schema 与输出 JSON，可读可测，且不让 MCP 的构建配置污染 prompt 那一半。

### 3.11 决策 9：`projectPath` 与 `outputSchema` 必须和内置工具对齐

两件事都是实测出来的差距，不是风格偏好。

**`projectPath`**：MCP Server 在派发到工具之前先解析目标项目，依据只有 header `IJ_MCP_SERVER_PROJECT_PATH`
或参数 `projectPath`。原以为"只打开一个项目"就够了——实测**不够**：不带参数调内置 `get_run_configurations`
（此时只开着一个项目）同样被拒。于是：

| 客户端配置        | 内置工具 | 本插件（修前）               |
| ----------------- | -------- | ---------------------------- |
| 有 header、无参数 | ✅       | ✅                           |
| 无 header、无参数 | ❌       | ❌                           |
| 无 header、有参数 | ✅       | ⚠️ 能跑，但 agent 不知道要传 |

内置 58 个工具里 **54 个**声明了 `projectPath`，没声明的恰好只有本插件那 4 个。补上之后两条路都通。

还有一个反直觉的服务器行为：**客户端给了 header 时，Server 会把所有工具的 `projectPath` 从 schema 里摘掉**
（官方工具一样，实测 56/56 都被摘）；只有没给 header 时才保留。这不是 bug，而是"会话级已经定好了目标项目，
就不必再让每次调用重复指定"。副作用是**验证时必须用不带 header 的 `tools/list`**，否则会误以为没声明。

**注意：这一点我们不需要自己去解析参数。** 客户端传了 `projectPath` 时，Server 已经把它解析进 `McpCallInfo`，
`coroutineContext.projectOrNull` 拿到的就是正确的项目。所以这一条纯粹是 schema 补齐，零实现改动。

**`outputSchema`**：3 个工具本来就用 `McpToolCallResult.text(摘要, 结构化JSON)` 返回，
线上 `content[].text` 与 `structuredContent` 都填好了（与内置工具的返回形态一致）。
缺的只是**声明**——内置 42/58 声明了 `outputSchema`。MCP 规范要求"返回 structuredContent 就应声明 outputSchema"，
否则严格客户端无法校验/类型化，可能直接忽略 `structuredContent`。可空字段（`pid` / `exitCode` / `startFailure` …）
必须写成 `["integer", "null"]`，否则客户端会在"进程还在跑"这种正常返回上校验失败。

---

## 4. 验证策略

| 层次            | 测试                              | 覆盖                                                                                                                   | 状态        |
| --------------- | --------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ----------- |
| prompt 格式契约 | `PromptFormatTest`（11 例）       | 两个契约的完整形态、语言标识大小写、缺失语言标识、出错行为空、排序稳定性                                               | ✅          |
| MCP 契约        | `RunLifecycleSchemaTest`（21 例） | 工具集合 / 名字合法性 / 只读与破坏性标注 / 必填参数 / 属性 schema 完整性 / 参数解析的边界 / 输出 JSON 的字段与 null 键 | ✅          |
| 平台集成        | `EditorDiagnosticsTest`（4 例）   | 诊断真的能从 markup model 采集到                                                                                       | ⛔ 禁用     |
| MCP 实际启停    | 无自动化                          | stop / rerun 的真实效果                                                                                                | 靠 `runIde` |

### 为什么没有 MCP 的平台集成测试

`stop` / `rerun` 的正确性依赖真实的 `ProcessHandler` 状态转换。要自动测就得在测试 IDE 里真起一个进程
（一个 sleep 脚本），再断言它被停掉了——这在 `BasePlatformTestCase` 里能做，但**当前跑不起来**（见下），
而且这种测试在 CI 上很脆（时序、平台差异）。因此选择：把能断言的契约（schema、JSON、参数解析）全部自动化，
平台行为靠 `./gradlew runIde` 手工验证，并在 `SPEC.md` 里把行为写死，让 review 有依据。

### 平台集成测试的已知阻塞

`BasePlatformTestCase` 在 **2026.2.3 上必然失败**：

```
Cannot create extension (class=Z.Z.Z.Z.Z) [Plugin: com.intellij.modules.ultimate]
  → Cannot find suitable constructor for class Z.Z.Z.Z.Z,
    expected (), (CoroutineScope), (Application), or (Application, CoroutineScope)
```

- `Z.Z.Z.Z.Z` 是 `lib/product-backend.jar` 里的平台内部（混淆）接口，失败发生在打开测试项目的 post-startup 阶段
- **与本插件无关**：把上游 `syllr/copy-as-prompt` 原样指向 2026.2.3，失败完全一致
- **绕不开**：2026.2 已停发 `ideaIC`，只能拉 Ultimate；去掉 `com.intellij.java`、加 `idea.force.use.core.classloader=true` 均无效
- 因此 `EditorDiagnosticsTest` 打了 `@Ignore`，**原因写在注解里**，修复后删注解即可恢复

---

## 5. 文件清单

| 文件                                                 | 层   | 说明                                                                                                                                  |
| ---------------------------------------------------- | ---- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `prompt/format/PromptFormat.kt`                      | 纯   | `formatAsPrompt` / `formatDiagnostics` / `Diagnostic` / `sortedForPrompt` / `TRAILING_BLANK`                                          |
| `prompt/actions/EditorPromptContext.kt`              | 平台 | `copyToClipboard` / `promptFilePath` / `languageTagOf` / `hasDiagnostics` / `collectDiagnostics`                                      |
| `prompt/actions/CopyAsPromptAction.kt`               | 平台 | 选区 action                                                                                                                           |
| `prompt/actions/CopyDiagnosticsAction.kt`            | 平台 | 诊断 action                                                                                                                           |
| `mcp/RunLifecycleModels.kt`                          | 纯   | `RunInstanceState` / `RunConfigurationState` / `StopReport` / `RerunReport` / `RerunMode` / `UnknownConfigurationReport` + `toJson()` |
| `mcp/RunLifecycleSchemas.kt`                         | 纯   | 三个 `McpToolDescriptor` + 输入 schema + 参数解析工具函数                                                                             |
| `mcp/RunConfigAccess.kt`                             | 平台 | `descriptorsByConfigurationName` / `snapshotOf` / `stopHandler` / `stopDescriptors` / `buildRunEnvironment` / `restartRunProfile`     |
| `mcp/RunLifecycleToolsProvider.kt`                   | 编排 | `RunLifecycleToolsProvider` + `BaseRunTool` + 三个工具                                                                                |
| `resources/META-INF/plugin.xml`                      | —    | 插件描述 + 2 个 action + 可选依赖声明                                                                                                 |
| `resources/META-INF/mcpserver.xml`                   | —    | 可选依赖专属：`mcpServer.mcpToolsProvider` 注册                                                                                       |
| `resources/META-INF/EULA.txt` / `PRIVACY_POLICY.txt` | —    | Marketplace 法务文本                                                                                                                  |
| `test/.../format/PromptFormatTest.kt`                | 纯   | prompt 格式契约                                                                                                                       |
| `test/.../mcp/RunLifecycleSchemaTest.kt`             | 纯   | MCP 契约                                                                                                                              |
| `test/.../actions/EditorDiagnosticsTest.kt`          | 平台 | 诊断采集（当前 `@Ignore`）                                                                                                            |
| `.github/workflows/build.yml`                        | —    | CI：test / build / verify / publish(tag)                                                                                              |

---

## 6. 资源管理

无长期持有的资源：无进程、无连接、无后台线程、无缓存、无 `Disposable`。

- prompt 侧：每次调用新建的 `StringSelection` 由 GC 回收；markup model 只读遍历，不注册监听器
- MCP 侧：`stopDescriptors` 里同步等待 `ProcessHandler.waitFor`，**不注册 `ProcessListener`**——因此即使
  工具调用超时被取消，也不会留下悬挂的回调

MCP 工具的等待上限（默认 5s）是**有意的**：避免 agent 卡在一个永不退出的进程上，也让 IDE 的事件循环
不会被长时间占住。

---

## 附录 A：已知限制与权衡

| 项                              | 现状                      | 说明 / 未来                                                                       |
| ------------------------------- | ------------------------- | --------------------------------------------------------------------------------- |
| 目标平台 262+                   | 不可降                    | MCP Server 是 2026.2 内置插件且未上 Marketplace，编译它的 API 会抬高低限          |
| `rerun` 不支持 `file:line`      | 只按配置名                | 内置 MCP 的 `get_run_configurations(filePath=...)` 已能给出可执行点，组合使用即可 |
| MCP 无自动化启停测试            | 靠 `runIde` 手工验证      | 当前 `BasePlatformTestCase` 在 2026.2.3 上不可用；恢复后应补                      |
| `stop` 的等待是同步阻塞         | 默认 5s 上限              | 事件循环友好的做法是挂 `ProcessListener` + suspend；当前调用量下同步更简单可靠    |
| executor 信息不在输出里         | 不返回                    | `ExecutionManager.getExecutors(descriptor)` 是 `@ApiStatus.Internal`，本插件不用  |
| 诊断的 quick-fix 建议           | 只输出消息 + 出错行       | `HighlightInfo` 有 quick fix 列表，但 API 偏 internal                             |
| 依赖 `HighlightInfo`（impl 包） | 已确认非 `@Internal`      | 若未来被废弃，改 `collectDiagnostics` 一处即可                                    |
| 无 i18n                         | 文案硬编码在 `plugin.xml` | 4 个 action/工具的文案量不值得维护 bundle                                         |
