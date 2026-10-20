# DESIGN.md — IDE MCP Enhance 设计文档

> **本文档定位**：项目级 **架构设计文档**。定义"如何实现"，不重复"必须满足什么"（那在 `SPEC.md`）。
>
> **阅读顺序**：`AGENTS.md` → `SPEC.md` → `DESIGN.md`

---

## 0. 项目概述

一个 JetBrains IDE 插件，由两个 **互不依赖**的能力组成：

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
|---------------------------------------------------------------|------------------------------------------------------|---------------------------|
| `prompt/format/`、`RunLifecycleModels`、`RunLifecycleSchemas` | 字符串 / `JsonObject` / mcpserver 的 descriptor 类型 | 平台 import、IO           |
| `prompt/actions/`、`RunConfigAccess.kt`                       | 平台 API                                             | 字符串格式化 / 手写 JSON  |
| `RunLifecycleToolsProvider.kt`                                | 编排                                                 | 直接操作 `ProcessHandler` |

**为什么这么切**：两个能力各有一样"对外契约"（prompt 文本 / MCP JSON），把契约做成纯函数，改契约的成本 = 改一个函数 +
改断言，不需要启动 IDE。反过来，平台接线集中在一层，出问题时知道去哪儿看。

---

## 2. Prompt 部分的关键决策

（与上游 `syllr/copy-as-prompt` 一致，完整推导见其 `DESIGN.md`）

| # | 决策                                         | 一句话理由                                                                                                        |
|---|----------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| 1 | 诊断来源用 document 级 `DocumentMarkupModel` | `DaemonCodeAnalyzerImpl.getHighlights` 带 `@TestOnly`；读 `editor.markupModel` 得到空列表且**不报错**（静默失败） |
| 2 | 严重级别用 `>= WARNING` 阈值而非枚举白名单   | 一个条件同时完成"过滤非诊断高亮"和"分级映射"                                                                      |
| 3 | 诊断范围是整个文件，不跟随选区               | 少给信息的代价高于多给信息                                                                                        |
| 4 | 路径优先相对项目根                           | 不把本机目录结构带进 prompt                                                                                       |
| 5 | 代码围栏带语言标识                           | 让渲染端高亮，也让 agent 少一次猜测                                                                               |
| 6 | 剪贴板直接用 AWT，不用 `CopyPasteManager`    | 后者带平台富文本语义，粘到浏览器输入框可能带格式                                                                  |
| 7 | 不用 `object` 单例、不引入 i18n              | 两个无状态 action，文案硬编码在 `plugin.xml`                                                                      |

详见 `prompt/format/PromptFormat.kt` 与 `EditorPromptContext.kt` 的 KDoc。

---

## 3. MCP 部分：从"起两个实例"到"真正的 Rerun"

### 3.1 问题是怎么发现的

内置 MCP 的 `execute_run_configuration` 每次调用都是 **无脑 spawn 一个新进程**：

```
第一次：PID 2100 占 5173
第二次：PID 2196 占 5174（日志：Port 5173 is in use, trying another one...）
```

MCP 侧完全不知道 2100 还活着——它没有状态查询，也没有停止语义。而 vite 不会因为端口被占就报错退出，它会 **自动换端口**
，所以肉眼极难发现多了一个实例。

想修这个，需要"停止"。而内置 MCP Server 没有。

### 3.2 被否掉的方案（都试过或分析过）

| 方案                                                  | 结论                                                                                                                                                                 |
|-------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 外部 `pkill` / 按端口杀                               | ❌ IDE 不知道进程没了，Run 窗口标签页仍显示"运行中"，下次点运行会撞端口                                                                                              |
| `package.json` 里前置 `stop && start`                 | ❌ 只覆盖 Node；每个技术栈都要重写一遍；本质是**改变项目的启动方式**而不是适配 IDE                                                                                   |
| Node 启动器 `run-with-pid.mjs` 记 PID                 | ❌ PID 来自 `child_process.spawn()` 的 `child.pid`（父进程才知道），不是 IDE 给的；只能包 Node 命令；`mvn spring-boot:run` 还会再 fork 一层，`child.pid` 是 maven 的 |
| 按端口 `lsof -ti tcp:5173` 反查                       | ⚠️ 语言无关、不改项目，但**依赖应用监听固定端口**；CLI / 随机端口场景走不通                                                                                          |
| Maven 里做（`exec-maven-plugin` / `spring-boot:run`） | ❌ `spring-boot:run` 没这个选项；塞进 `pom.xml` 会让 CI 跑 `mvn package` 也被误伤                                                                                    |
| **写 IDEA 插件，走 IDE API**                          | ✅ 语言无关、不改项目、拿得到 `ProcessHandler`                                                                                                                       |

### 3.3 决策 1：停止用公开 API 重新实现，不用 `ExecutionManagerImpl`

平台的 Stop 按钮走 `ExecutionManagerImpl.stopProcess(descriptor)`。那是 `com.intellij.execution.impl` 包，
`@ApiStatus.Internal`。本插件改为用 **等价的公开 API 组合**（`RunConfigAccess.stopHandler`）：

```kotlin
handler.putUserData(ProcessHandler.TERMINATION_REQUESTED, true)   // ① 平台知道"用户主动停的"
if (handler is KillableProcess && handler.isProcessTerminating) handler.killProcess()  // ② 已卡住就强杀
else if (!handler.isProcessTerminated) {
    if (handler.detachIsDefault()) handler.detachProcess() else handler.destroyProcess() // ③ 与平台一致
}
```

平台实现把 ②③ 放在 `Dispatchers.Default` 上异步做；本插件同步做——`killProcess` / `destroyProcess`
都只是非阻塞的信号投递，同步调用不会卡住调用线程。

**代价**：平台改了停止语义，这里要跟着改。 **收益**：不绑死在一个 `impl` 包上。

### 3.4 决策 2：实例归属靠"事件盖章"，不靠 `getRunConfigurationName()`

要回答"这个配置有几个实例在跑"，就得把 `RunContentDescriptor` 关联回配置——而平台 **没有**公开 API 能做这件事：

- `getRunProfile()`：2026.1 有， **2026.2 删了**
- `getRunConfigurationName()` / `getRunConfigurationTypeId()` / `ExecutionManager.getRunningDescriptors(...)`： **全是
  `@ApiStatus.Internal`**（Plugin Verifier 会把前者报成 internal API usage；本仓库 HARD RULE 也禁止内部 API）
- `getDisplayName()`：UI 文本，多实例时会被加 "(2)" 之类后缀，拿它和配置名比对必然失配——而"同一配置有几个实例"恰恰是要回答的核心问题

**做法**：在 `ExecutionListener.processStarting` / `processStarted` 事件里把配置名写进 `ProcessHandler`（
`ProcessHandler extends UserDataHolderBase`），读的时候用公开的 `descriptor.processHandler` 取回同一个 handler 上的戳。事件由
`ExecutionManagerImpl` 发出，IDE 的 Run/Rerun 按钮、内置 `execute_run_configuration`、我们自己的 `rerun` 三条路都会经过。

**代价**：取不到戳的 Run 内容会被跳过（宁可少列一个，也不猜一个错的名字）。 **收益**：只依赖公开
API，且状态挂在平台自己的对象上，没有本插件侧的全局可变状态。详见 `mcp/RunConfigurationStamping.kt` 的 KDoc。

### 3.5 决策 3：`rerun` 自己接 `ProgramRunner.Callback` 同步等启动，而不是 `restartRunProfile` /
`ExecutionUtil.restartIfActive`

`ExecutionUtil.restartIfActive(descriptor)` 看起来正合适，但它内部是：

```java
ExecutionEnvironment env = ExecutionDataKeys.EXECUTION_ENVIRONMENT.getData(
        DataManager.getInstance().getDataContext(descriptor.getComponent()));
if(env !=null)

restart(env);
```

**从 UI 组件的 DataContext 里捞环境**——这依赖 Run 工具窗口的组件树，在 MCP 这条无头路径上不可靠。

`ExecutionManager.restartRunProfile(ExecutionEnvironment)` 是 Rerun 按钮走的路径，但它是 **发射后不管**的：调用后立刻
快照，新实例的 `RunContentDescriptor` 还没注册，`runningAfter` 必然读到 0。实测过一次：

```json
{
  "mode": "STARTED",
  "runningAfter": 0,
  "instances": [
    {
      "executionId": 5,
      "running": false
    },
    {
      "executionId": 7,
      "running": false
    }
  ]
}
```

而人话摘要写着 "Started ... — it was not running."；1 秒后再查还是空，4 秒后新实例（`executionId 8, pid 28247`）才出现。
agent 完全会把它读成"启动失败"，进而重复启动或误报。

内置 MCP 的 `execute_run_configuration` 没有这个问题：它的文档明确写 _"When `waitForExit=false`, waits only for the
process to start, then returns immediately"_。
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

**提交前必须先过三道判断**（都用公开 API，不自己造状态；第三道是兜底）。 **调用时机**：`rerunNow` 在 **停旧实例之前**
先问一次（否则会先把在跑的应用杀掉、再告诉用户去 IDE 处理），`startRunAndAwait` 在 **真正提交之前**再问一次（状态可能在停止期间变化）：

1. `settings.checkSettings(executor)` —— 平台在 `ExecutionManagerImpl.executeConfiguration` 里先问
   `RunManagerImpl.canRunConfiguration`（实现就是它），为 false 时 **弹 `RunDialog.editConfiguration`
   模态对话框**。无头 / agent 场景没人点它，EDT 会被永久占住，我们那次
   `withContext(Dispatchers.EDT)` 的提交就再也回不来（`jstack` 实测抓到这个栈）。所以提前问一次，
   把 `IndexNotReadyException` / `RuntimeConfigurationError` 转成立刻、可读的结构化错误。
2. `ExecutionManager.isStarting(environment)` —— `executeConfigurationAsync` 的第一句就是它，
   命中时这次提交被 **静默丢弃**（连回调都不装）。`inProgress` 从 `executeConfiguration` 一进来就加、
   进程创建并 `startNotify` 之后才减，所以 **整个启动过程（含 before-launch 构建）都算"启动中"**，
   这个门能覆盖"构建中又发一次 rerun"的长窗口。

3. `DialogWrapper.findInstanceFromFocus()?.isModal` —— "现在是不是有模态对话框在等用户"。
   这是 **通用**的 EDT 卡点检测（`checkSettings` 只管"这个配置能不能跑"），命中就立刻返回
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
|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `list_running_...` | 内置的 `get_run_configurations` 只给**静态配置信息**（名字、是否支持启动参数覆盖），不给运行状态；本插件只报在跑的，整份返回里不含任何已结束的实例 |
| `stop_...`         | 补最关键的缺口；且**停掉全部实例**——只停最新的那个等于没修端口漂移                                                                                 |
| `rerun_...`        | 改完代码重启，是本插件存在的首要理由                                                                                                               |

`stop` 幂等（对没在跑的配置返回"无需停止"而非报错），`rerun` 不幂等（每次都是新的一次启动）。

**第四个工具 `get_..._status` 被砍掉了**（曾经存在过）：它按名字查单个配置的状态，但经实测判定冗余 ——
"名字纠错"`stop` / `rerun` 本来就有（同一套 `findSettingsByName` + `unknownConfiguration`），
"看实时状态"由 `list_running_...` 覆盖（项目里并发的作业本来就不多，全量拉一次很便宜），
它剩下的唯一价值只是"省一次全量拉取"。工具少一个，agent 少一次选择。

### 3.7 决策 5：null 键保留不省略

`pid` / `exitCode` 在进程没起来时是 null。JSON 里如果省略这两个键，agent 无法区分"工具出错了所以没这个字段"和"进程还没启动所以
pid 还没有"。因此一律输出 `"pid": null`。

### 3.8 决策 6：`stop` 的等待与强杀

`ProcessHandler.waitFor(timeoutMs)` 会阻塞，所以给一个 **共享的截止时间**而不是每个进程各等一轮：

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

`mcpserver.xml` 只在 MCP Server 存在时被读取，里面注册 `RunLifecycleToolsProvider`。因此所有引用`com.intellij.mcpserver.*`
的类 **只在那一刻才可能被加载**——没有 MCP Server 的 IDE 上，它们连解析都不会发生。

这是 JetBrains 插件处理可选依赖的标准姿势： **主 `plugin.xml` 里不许出现任何可选插件的类型**。

### 3.10 决策 8：为什么用 `McpToolsProvider` 而不是 `McpToolset`

见 `AGENTS.md` 的「MCP 扩展点的真实形状」。简述：`McpToolset` 那条路是反射式的，要挂 kotlinx-serialization 的编译器插件，返回类型必须
`@Serializable`；`McpToolsProvider` 让我们手写 schema 与输出 JSON，可读可测，且不让 MCP 的构建配置污染 prompt 那一半。

### 3.11 决策 9：`projectPath` 与 `outputSchema` 必须和内置工具对齐

两件事都是实测出来的差距，不是风格偏好。

**`projectPath`**：MCP Server 在派发到工具之前先解析目标项目，依据只有 header `IJ_MCP_SERVER_PROJECT_PATH`
或参数 `projectPath`。原以为"只打开一个项目"就够了——实测 **不够**：不带参数调内置 `get_run_configurations`
（此时只开着一个项目）同样被拒。于是：

| 客户端配置        | 内置工具 | 本插件（修前）               |
|-------------------|----------|------------------------------|
| 有 header、无参数 | ✅       | ✅                           |
| 无 header、无参数 | ❌       | ❌                           |
| 无 header、有参数 | ✅       | ⚠️ 能跑，但 agent 不知道要传 |

内置 58 个工具里 **54 个**声明了 `projectPath`，没声明的恰好只有本插件那 4 个。补上之后两条路都通。

还有一个反直觉的服务器行为： **客户端给了 header 时，Server 会把所有工具的 `projectPath` 从 schema 里摘掉**
（官方工具一样，实测 56/56 都被摘）；只有没给 header 时才保留。这不是 bug，而是"会话级已经定好了目标项目，
就不必再让每次调用重复指定"。副作用是 **验证时必须用不带 header 的 `tools/list`**，否则会误以为没声明。

**注意：这一点我们不需要自己去解析参数。** 客户端传了 `projectPath` 时，Server 已经把它解析进 `McpCallInfo`，
`coroutineContext.projectOrNull` 拿到的就是正确的项目。所以这一条纯粹是 schema 补齐，零实现改动。

**`outputSchema`**：3 个工具本来就用 `McpToolCallResult.text(摘要, 结构化JSON)` 返回，
线上 `content[].text` 与 `structuredContent` 都填好了（与内置工具的返回形态一致）。
缺的只是 **声明**——内置 42/58 声明了 `outputSchema`。MCP 规范要求"返回 structuredContent 就应声明 outputSchema"，
否则严格客户端无法校验/类型化，可能直接忽略 `structuredContent`。可空字段（`pid` / `exitCode` / `startFailure` …）
必须写成 `["integer", "null"]`，否则客户端会在"进程还在跑"这种正常返回上校验失败。

---

## 4. 验证策略

| 层次            | 测试                              | 覆盖                                                                                                                   | 状态        |
|-----------------|-----------------------------------|------------------------------------------------------------------------------------------------------------------------|-------------|
| prompt 格式契约 | `PromptFormatTest`（11 例）       | 两个契约的完整形态、语言标识大小写、缺失语言标识、出错行为空、排序稳定性                                               | ✅          |
| MCP 契约        | `RunLifecycleSchemaTest`（21 例） | 工具集合 / 名字合法性 / 只读与破坏性标注 / 必填参数 / 属性 schema 完整性 / 参数解析的边界 / 输出 JSON 的字段与 null 键 | ✅          |
| 平台集成        | `EditorDiagnosticsTest`（4 例）   | 诊断真的能从 markup model 采集到                                                                                       | ⛔ 禁用     |
| MCP 实际启停    | 无自动化                          | stop / rerun 的真实效果                                                                                                | 靠 `runIde` |

### 为什么没有 MCP 的平台集成测试

`stop` / `rerun` 的正确性依赖真实的 `ProcessHandler` 状态转换。要自动测就得在测试 IDE 里真起一个进程
（一个 sleep 脚本），再断言它被停掉了——这在 `BasePlatformTestCase` 里能做，但 **当前跑不起来**（见下），
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
- **绕不开**：2026.2 已停发 `ideaIC`，只能拉 Ultimate；去掉 `com.intellij.java`、加 `idea.force.use.core.classloader=true`
  均无效
- 因此 `EditorDiagnosticsTest` 打了 `@Ignore`， **原因写在注解里**，修复后删注解即可恢复

---

## 5. 文件清单

| 文件                                                 | 层   | 说明                                                                                                                                  |
|------------------------------------------------------|------|---------------------------------------------------------------------------------------------------------------------------------------|
| `prompt/format/PromptFormat.kt`                      | 纯   | `formatAsPrompt` / `formatDiagnostics` / `Diagnostic` / `sortedForPrompt` / `TRAILING_BLANK`                                          |
| `prompt/actions/EditorPromptContext.kt`              | 平台 | `copyToClipboard` / `promptFilePath` / `languageTagOf` / `hasDiagnostics` / `collectDiagnostics`                                      |
| `prompt/actions/CopyAsPromptAction.kt`               | 平台 | 选区 action                                                                                                                           |
| `prompt/actions/CopyDiagnosticsAction.kt`            | 平台 | 诊断 action                                                                                                                           |
| `mcp/RunLifecycleModels.kt`                          | 纯   | `RunInstanceState` / `RunConfigurationState` / `StopReport` / `RerunReport` / `RerunMode` / `UnknownConfigurationReport` + `toJson()` |
| `mcp/RunLifecycleSchemas.kt`                         | 纯   | 三个 `McpToolDescriptor` + 输入 schema + 参数解析工具函数                                                                             |
| `mcp/RunConfigAccess.kt`                             | 平台 | `descriptorsByConfigurationName` / `snapshotOf` / `stopHandler` / `stopDescriptors` / `buildRunEnvironment` / `startRunAndAwait`      |
| `mcp/RunLogModels.kt`                                | 纯   | 日志键 / 文件名 / 历史条目 / JSON                                                                                                     |
| `mcp/RunLogAccess.kt`                                | 平台 | 启动即采集输出、sidecar、历史扫描、改名迁移、过期清理                                                                                 |
| `mcp/RunConfigNaming.kt`                             | 纯   | `[os@]base[@env]` 解析                                                                                                                |
| `mcp/RunConfigDefinitionModels.kt`                   | 纯   | `RunConfigurationInspection` + JSON                                                                                                   |
| `mcp/RunConfigDefinitionAccess.kt`                   | 平台 | 读写 `.run.xml`、解析 options / envs / method、拼回 IDE 格式                                                                          |
| `mcp/RunConfigAuthoringAccess.kt`                    | 平台 | 类型查找、平台校验、模板、引用改写、VFS 删除                                                                                          |
| `mcp/RunConfigDefinitionSchemas.kt`                  | 纯   | 定义态 4 个描述符                                                                                                                     |
| `mcp/RunConfigDefinitionToolsProvider.kt`            | 编排 | `inspect` / `create` / `update` / `delete`                                                                                            |
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

**运行态工具**本身不长期持有资源：`stopDescriptors` 里同步等待 `ProcessHandler.waitFor`，工具调用结束即释放。

**日志采集**是唯一一处长期挂在平台对象上的东西，且是刻意的：

- 每个能启动的进程挂一个 `ProcessListener`（见 §7.3）。它随进程 `ProcessHandler` 一起被回收，
  不需要我们手动注销
- 日志文件在 `PathManager.getTempDir()`，单文件 8 MB 封顶；IDE 启动时清理 7 天前的旧文件
- 没有静态全局可变状态：配置名、日志路径、开始时刻都盖在 `ProcessHandler` 自己身上（`UserDataHolderBase`）

MCP 工具的等待上限（默认 5s / 10s）是 **有意的**：避免 agent 卡在一个永不退出的进程上，也让 IDE 的
事件循环不会被长时间占住。

---

## 7. 定义态工具集：Run Configuration 的增删改查 + 运行日志

前面 §3 解决的是 **运行态**（谁在跑、停、重启）。这一章解决 **定义态**：一个 Run Configuration
长什么样、怎么建、怎么改、怎么删，以及"上次那次跑的日志在哪"。

### 7.1 工具集与职责边界

| 工具                                                 | 定位      | 回答什么                                     |
|------------------------------------------------------|-----------|----------------------------------------------|
| `list_running_configurations`                        | **索引**  | 谁在跑、几个实例、各自 pid / 状态 / 日志路径 |
| `inspect_run_configuration`                          | **详情**  | 这个配置是什么、跑过几次、每次的日志在哪     |
| `create_run_configuration`                           | 建        | 建任意类型                                   |
| `update_run_configuration`                           | 改 + 改名 | 改字段、改名字（连带更新别人对它的引用）     |
| `delete_run_configuration`                           | 删        | 删掉它                                       |
| `stop_run_configuration` / `rerun_run_configuration` | 动作      | 停止 / 重启的执行报告                        |

**"一个产物一个写入口"**：`.run/*.run.xml` 的增删改查只走上面三个 CRUD 工具。
这条规则 **只写在提示词里**（工具描述 + opencode-idea 侧的 guidance / skill）， **不做硬拦截** ——
硬拦会让"用户明确要求手改某个文件"这件事直接失败，
比现在更糟；而且原生 `write` / `shell` 本来也拦不住，只会造成"看起来有保障其实没有"。

**枚举不重复造轮子**：内置的 `get_run_configurations` 已经回答"项目里有哪些配置"，
所以 **不新增** `list_run_configurations`；定义态的读取由 `inspect_run_configuration` 承担。

**旧的 Shell 专用创建工具已删除**（`create_shell_run_configuration`）：它本来就是通用 create 的一个子集，
留着只会让"写文件 / 平台校验 / 默认值归一化"出现第二套实现。现在建 Shell 配置直接用
`create_run_configuration`（`type: "ShConfigurationType"`，或 `cloneFrom` 一个现成的 Shell 配置）。

### 7.2 一个配置、多个实例、一个实例一份日志

一个 Run Configuration 可以有 **多个活实例**（内置 `execute_run_configuration` 每次调用都 spawn
一个新进程），而 **一次运行 = 一个进程 = 一个日志文件**。因此：

- `list` / `inspect` 里的最小单位是 **实例**，每个实例带自己的 pid、状态、日志路径
- 两个实例 → 两个日志文件，agent 能分别去看（"哪个实例的日志里有报错"）

### 7.3 日志采集：必须在进程启动的那一刻接上

**平台没有公开 API 能事后读控制台内容**：`RunContentDescriptor.getExecutionConsole()` 是公开的，
但它给出的 `ConsoleView` 只有 `getContentSize()`；能取文本的 `ConsoleViewImpl` 在
`com.intellij.execution.impl` 包里，本仓库禁用（见 `AGENTS.md` 的 HARD RULES）。

所以日志只能在 **进程启动时**开始抓。抓点就是现有的"事件盖章"机制
（见 §3.4）：`ExecutionListener.processStarting(executorId, env, handler)`。

**字节码核实（2026.2.3）**：`ExecutionManagerImpl.doStartRunProfile$lambda$1$0` 里
`processStarting(...)` 在 offset 302，紧跟着 offset 309 就是 `processHandler.startNotify()` ———
事件在进程吐第一个字 **之前**发出，所以挂上 listener 不会漏首行。

覆盖范围（都经过 `DefaultProgramRunner` / `GenericProgramRunner` →
`ExecutionManager.startRunProfile(env, promise)` → `doStartRunProfile`）：

| 启动方式                                     | 有日志               |
|----------------------------------------------|----------------------|
| IDE 的 Run / Rerun 按钮                      | ✅                   |
| 内置 `execute_run_configuration`             | ✅                   |
| 本插件的 `rerun`                             | ✅                   |
| before-launch 里"跑另一个 run configuration" | ✅（子配置自己一份） |

### 7.4 历史实例：平台不留痕，我们自己留

Run 标签页一关，平台侧 **什么都不剩**（`RunManager` 不存运行历史，`ExecutionManager` 只给活着的
descriptor）。所以"这个配置上次跑出来什么日志"只能靠我们写在盘上的痕迹回答。

**两个产物**（放在 `PathManager.getTempDir()`，与官方 `execute_run_configuration` 同目录）：

```
ij_enhance_run__<key16>_<startedAtMillis>_<rand4>.log   ← 那次跑的输出（本体）
ij_enhance_run__<key16>_<startedAtMillis>_<rand4>.json  ← 富化：pid / exitCode / endedAt
```

**`key16` 的定义**：`sha256(projectBasePath + "\0" + configurationName)` 的前 16 位十六进制。

- 为什么不用官方的 `sanitizeFileName`： **不可逆**，`console-web` 与 `console_web` 会被抹成同一个名字，
  然后我们会把别人的日志当成这个配置的返回
- 为什么带项目路径：两个项目里同名的配置不能混在一起
- 为什么是十六进制：只含小写字母数字， **大小写不敏感的 macOS 文件系统上也不会撞名**

**排序与身份**：文件名里就有 `startedAtMillis`， **时间戳就是排序键**。
历史条目按 `startedAt` 倒序返回（agent 第一眼看到的就是最近一次）。
`rand4` 只为防文件名撞车 —— 复合配置会在同一毫秒起多个成员，不带随机后缀会互相覆盖。

**不出现实例 ID**：死掉的实例，`executionId` 指向一个 IDE 已经不再持有的东西，agent 拿它既不能停
也不能查日志（我们的 `stop` / `rerun` 都是按配置名来的）。所以历史条目里 **没有 ID**，
身份就是 **日志文件路径**（一次运行一个文件，天然唯一）。

**sidecar 是可选的**：进程终止时才写（那时才知道 exitCode）。它没了也不影响"日志还在就返回" —— **日志文件才是本体**，sidecar
只是富化。IDE 崩溃 / 被强杀时 sidecar 会缺失，那条历史就只剩
`startedAt` + 日志路径，其余字段为 `null`（按 §3.7 的约定，null 键保留不省略）。

### 7.5 采集不到的四种情况（必须如实说，不能假装有）

| 情况                                           | 为什么没有日志                                                                                                                               |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| 终端形态的 Shell（`EXECUTE_IN_TERMINAL=true`） | `ShRunConfigurationProfileState.execute` 在终端分支直接 `return null`，**不创建 ProcessHandler**；脚本输出进 IDE 终端，不经过 ProcessHandler |
| before-launch 的 Make / Build 步骤             | `compileAndRun` 用平台自己的 `MyProcessHandler`，那段字节码里**不发 `ExecutionListener` 事件**                                               |
| 插件启用之前就启动的进程                       | 监听是项目打开时挂的                                                                                                                         |
| 建日志文件失败（磁盘满 / 权限）                | 建文件失败就返回 null，实例照常上报，`logFile` 为 `null`                                                                                     |

终端形态那条还有连带影响： **这类实例在运行态工具里是隐形的** ——
`toInstanceState` 对 `processHandler == null` 返回 null，所以 `list` / `stop` / `rerun` 都看不见它。
它不是"没日志"，是 **平台接管不了**。

### 7.6 通用 create：让平台自己校验

"所有类型都能建"不等于"为每种类型写一套代码"。路线是 **用平台自己的序列化器**：

1. `ConfigurationTypeUtil.findConfigurationType(typeId)` 找类型；找不到 → 报"这个 IDE 没装对应插件"
2. 三种内容来源 **三选一**：
    - `cloneFrom`（最安全）：复制一个已存在的同类型配置
    - 平台模板：`RunManager.getConfigurationTemplate(factory)` 拿该类型的默认配置
    - `configurationXml`：调用方给的 XML 片段（逃生舱）
3. 把内容灌进一个临时配置对象：`RunConfiguration.readExternal(Element)` —— **该类型自己的代码**在解析
4. 再 `writeExternal(Element)` 序列化回来，得到平台认可的规范 XML
5. **往返对比**：请求里给了、序列化后消失的字段 = 这个类型不认识 → 作为 warning 返回
   （对应 `AGENTS.md` 里的老坑"多余的字段被忽略、名字写错静默用默认值，都不会报错"）
6. 写盘：包进 `<component name="ProjectRunConfigurationManager">` 信封，写进 `.run/<name>.run.xml`
7. 等 IDE 的文件监听注册它，再用 `checkSettings` 报 `canRun`

> **能力边界**：工具覆盖任意类型，但 **不代表 agent 能凭空知道某个类型的字段名**。
> 全新类型最稳的路径仍是"用户在 IDE 里建一个样例 → agent `cloneFrom` / `inspect` 后照抄"。
> 这比"让 agent 猜 XML"可靠得多，所以 `cloneFrom` 是一等公民。

### 7.7 update 的两种模式

| 模式   | 触发           | 做什么                                                                |
|--------|----------------|-----------------------------------------------------------------------|
| 改字段 | 不传 `newName` | 按名字打补丁：`options` / `attributes` / `envs`，可增改删             |
| 改名   | 传 `newName`   | 挪文件 + 改 `name` 属性 + **更新别人对它的引用** + 按新名字重建日志键 |

改名的额外规则：

- 目标名被占用 → 拒绝，列出相近的名字
- 名字非法（`/`、`\`、空、过长）→ 拒绝
- **该配置在跑 → 拒绝**，提示先 `stop_run_configuration`：运行实例是按名字盖的章，
  改名后 `list` 与 IDE Run 窗口会对不上
- 模板 / 非 `.run/` 存储 → 拒绝（只读）
- 引用更新范围：别的 `.run/*.run.xml` 里 before-launch 的 `run_configuration_name`、复合配置的成员名；
  workspace / `.idea` 里的引用不动，作为 warning 说明

实现顺序上先写新文件、再删旧文件；中途失败如实报"已建新名、旧名还在"，不做半吊子回滚。

### 7.8 delete 的引用检查

`delete` 单独一个工具而不是 `update` 的一个模式：两者安全等级不同 ——
`update` 是幂等收敛的（改完还是同一个配置），`delete` 是"让东西消失"，不可逆。

- 在跑 → 拒绝，提示先停
- 被引用（before-launch / 复合配置成员）→ 拒绝并列出引用者
- 模板 / 非 `.run/` 存储 → 拒绝

### 7.9 命名文法：`[os@]base[@env]`

平台对配置名没有任何结构理解，但我们的约定有（见 opencode-idea 侧的 skill）：

| 段     | 位置 | 取值     | 缺省               |
|--------|------|----------|--------------------|
| `os`   | 最前 | `win`    | macOS / Linux      |
| `base` | 中间 | 配置本体 | 必填               |
| `env`  | 最后 | 环境名   | 所有环境通用这一套 |

解析是 **纯函数**（可单测）：按 `@` 切分，最多三段；两段时首段是 `win` 就算平台段，
否则算环境段。解析结果由 `inspect_run_configuration` 的 `naming` 字段给出 ——
让"这个配置是哪个 base 的哪个变体"成为工具给出的事实，而不是 agent 自己去猜。

---

## 附录 A：已知限制与权衡

| 项                              | 现状                      | 说明 / 未来                                                                       |
|---------------------------------|---------------------------|-----------------------------------------------------------------------------------|
| 目标平台 262+                   | 不可降                    | MCP Server 是 2026.2 内置插件且未上 Marketplace，编译它的 API 会抬高低限          |
| `rerun` 不支持 `file:line`      | 只按配置名                | 内置 MCP 的 `get_run_configurations(filePath=...)` 已能给出可执行点，组合使用即可 |
| MCP 无自动化启停测试            | 靠 `runIde` 手工验证      | 当前 `BasePlatformTestCase` 在 2026.2.3 上不可用；恢复后应补                      |
| `stop` 的等待是同步阻塞         | 默认 5s 上限              | 事件循环友好的做法是挂 `ProcessListener` + suspend；当前调用量下同步更简单可靠    |
| executor 信息不在输出里         | 不返回                    | `ExecutionManager.getExecutors(descriptor)` 是 `@ApiStatus.Internal`，本插件不用  |
| 诊断的 quick-fix 建议           | 只输出消息 + 出错行       | `HighlightInfo` 有 quick fix 列表，但 API 偏 internal                             |
| 依赖 `HighlightInfo`（impl 包） | 已确认非 `@Internal`      | 若未来被废弃，改 `collectDiagnostics` 一处即可                                    |
| 无 i18n                         | 文案硬编码在 `plugin.xml` | 4 个 action/工具的文案量不值得维护 bundle                                         |

## 附录 B：平台细节备忘

从 AGENTS.md 搬过来的机制细节（**规则**在 AGENTS.md，这里是「为什么」）。条目之间无先后。

- **`writeExternal` 不带 `<envs>` / `<method>`**：`RunConfigurationBase.writeExternal` 只序列化类型自己的
  `options`。所以 **读**定义要走盘上的 `.run.xml`（完整），不要指望平台序列化能拼回整份文件；
  round-trip 对比只对 `<option>` 的字段名有效
- **2026.1 → 2026.2 的 API 变化**：`RunContentDescriptor` **没有** `getRunProfile()` 了；而接替它的
  `getRunConfigurationName()` / `getRunConfigurationTypeId()`，以及 `ExecutionManager.getRunningDescriptors(...)` /
  `getExecutors(descriptor)` —— **全都是 `@ApiStatus.Internal`**，本插件一个都不用（实例归属见下面那条"事件盖章"）
- **平台没有 `ExecutionManager.stopRunProfile`**: Stop 按钮走 `ExecutionManagerImpl.stopProcess`（`impl` 包）。本插件用等价的公开
  API 组合重写（`TERMINATION_REQUESTED` + `killProcess`/`detachProcess`/`destroyProcess`），语义逐条对齐，见
  `RunConfigAccess.stopHandler` 的 KDoc
- **`McpToolSchema.propertiesSchema` 只装 properties**，不含根级 `"type": "object"`；根类型由 MCP Server 合成 schema
  时补（input 与 output 都一样）
- **服务器会按客户端配置裁剪 schema**：客户端给了 header 时，它会把 **所有**工具的 `projectPath` 参数从 schema
  里摘掉（官方工具也一样）；只在没给 header 时才保留。所以验"有没有声明 projectPath"必须用 **不带 header**的 `tools/list`
  ，否则会误判成"没声明"
- **启动 Run Configuration 是异步的**：`ExecutionManager.restartRunProfile(env)` 发射后不管，紧接着做快照必然读到
  `runningAfter = 0`、`instances[]` 里只有旧的已死实例。必须走
  `ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)` + `ProgramRunner.Callback`，在
  `processStarted` 里拿到 `descriptor` 才算"起来了"（内置 `execute_run_configuration` 也是这么做的）
- **`executeConfigurationAsync` 的第一句就是平台自己的门**：`if (!manager.isStarting(environment)) { … }` —— 同配置 + 同
  executor + 同 runner 正在启动时，这次提交被 **静默丢弃**（回调都不装，既不回 `processStarted` 也不回
  `processNotStarted`）。`isStarting` 读的是 `inProgress`，而 `inProgress` 是 `executeConfiguration` 一进来就加、 **进程创建并
  `startNotify` 之后才减**（`ExecutionManagerImpl.kt` 里 `inProgress.update { it - inProgressEntry }` 紧跟
  `processHandler.startNotify()`）⇒ **碰撞窗口 = 整个启动过程**， **包含 before-launch 的构建时长**（实测：给配置挂一个永不退出的
  before-launch 后，第二次 rerun 0 秒返回 "another start is already in
  progress"；我早先说"窗口只有进程创建那几十毫秒"是错的）。 **平台永远不会起两个实例**，所以"防双实例"不需要我们做；`rerun`
  只需在发起前用同一个条件问一次，把"静默丢弃"换成立刻、精确的错误
- **before-launch 步骤可以查出来**：`RunConfiguration.getBeforeRunTasks()`（公开默认方法）。平台在它们跑完之前 **不产出 Run
  内容**（实测：给 `node-server` 挂一个永不退出的 before-launch 后，`list` 里只有那个 before-launch 配置有实例、
  `node-server` 什么都没有）。所以启动超时的文案必须点明这一点，而不是让人以为启动失败
- **中间态判定真的能抓到**（实测，不是推理）：`stop` 等待进程退出的窗口里，另一会话 rerun 会拿到
  `isError=true` + `"…is currently stopping, and the IDE disables Rerun in that state."`。判据与 GUI 同源：
  平台的 `FakeRerunAction.isEnabled` 判 `isStarting()` / `isProcessTerminating()`，我们按 **每个实例**读
  `ProcessHandler` 的同一对状态（`startNotified` / `isProcessTerminating`），粒度更细、且不依赖 UI 组件树
- **`RunContentDescriptor` 没有任何公开的"配置归属"API**：`getRunConfigurationName()` 与
  `ExecutionManager.getRunningDescriptors(...)` 从 262 起就都是 `@ApiStatus.Internal`（Plugin Verifier 在 2026.3 上把它报成
  internal API usage），而 `displayName` 是多实例会被加后缀的 UI 文本。归属因此改成"事件盖章"：
  `ExecutionListener.processStarting/processStarted`（由 `ExecutionManagerImpl` 发出；IDE 的 Run/Rerun 按钮、内置
  `execute_run_configuration`、我们自己的 `rerun` 三条路都经过它）把配置名写进 `ProcessHandler`（
  `ProcessHandler extends UserDataHolderBase`），读的时候用公开的 `descriptor.processHandler` 取回同一个 handler 上的戳——见
  `mcp/RunConfigurationStamping.kt`。 **没有**戳的 Run 内容会被跳过：宁可少列一个，也不猜一个错的名字
- **`HighlightInfo` 在 `...daemon.impl` 包里，但只是 `@ApiStatus.NonExtendable`**，可以用
- **daemon 高亮存在 document 级 `DocumentMarkupModel`**，不是 `editor.markupModel`。读错地方会得到空列表（不报错，静默失败）
- **写完 `.run.xml` MUST 调 `LocalFileSystem.refreshAndFindFileByNioFile`**，别只用 `Files.writeString`：
  `RCInArbitraryFileListener` 只处理 `LocalFileSystem` 的 `VFileCreateEvent`，纯 nio 写的文件 VFS 注意不到就
  **既不触发监听器也不进配置列表**（实测：文件确实落盘，但 IDE 等满 3 秒也没注册，工具只能如实报"尚未加载"）。刷新一次就正常（实测
  `canRun` 立刻变 true，`rerun` 立刻能跑）。两个入口都是公开 API，也都不在 `execution.impl` 包里
- **Deployment 的两类配置粒度不同，很容易读错**：`Mappings` 与 `Excluded paths` 是 **按服务器**的（存在
  `PublishConfig$ServerData`，按服务器名/ID 索引），而 **其余全部选项**（`overwriteAll`、`deleteMissingItems`
  、确认开关、自动上传、排除掩码、权限）都是 **项目级、所有服务器共用**。所以 `list_deployment_servers` 返回里的 `options`
  描述的是"这个项目的部署策略"，不是"某个服务器的属性"——想按环境差异化行为只能靠调用参数（`PublishConfig.clone()` 改一份副本传给
  `TransferTask`，不碰用户的持久配置），不能靠配置本身
- **`overwriteAll` 的语义与字面相反**：`true`（ **默认值**）表示"不做 size+mtime 比较、每次全量覆盖"——
  `TransferOperation.copyFile` 里是 `if (!isOverwriteAll()) { if (isUpToDate(remote, local, server)) skip }`，为 true
  时那段跳过逻辑压根不执行。配套的 `preserveTimestamps`（默认 true）是"上传后把远端 mtime 设成本地 mtime"，
  **关掉它下次就再也没法跳过**（mtime 对不上）
- **"删远端多余"靠 `deleteMissingItems`，它不是"上传的某个参数"**：上传任务走 `DeploymentMode.CUSTOM`（`TransferTask`
  的构造器默认就传它），`CUSTOM.sourceNotExist` 里判 `isDeleteMissingItems()` 决定是否生成 `Delete`；若
  `confirmBeforeDeletion` 为 true 则生成 `UserConfirmedDelete` —— **那个会弹模态框**，MCP 路径必须回避（
  `PublishConfig.clone()` 里强制设 false）
