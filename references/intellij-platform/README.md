# IntelliJ Platform 参考笔记

开发本插件时反复要查的东西，集中放在这里，避免每次都去翻 jar。

---

## 1. 2026.1 → 2026.2 的 API 变化（踩过的坑）

### `RunContentDescriptor`：既没有 `getRunProfile()`，也没有公开的"配置归属"

2026.1 有 `getRunProfile()`，2026.2 **删了**。现在能看到这些：

```java
String getRunConfigurationName();     // @ApiStatus.Internal（262 起就是）—— 不能用
String getRunConfigurationTypeId();   // @ApiStatus.Internal —— 不能用
long   getExecutionId();              // 公开
ProcessHandler getProcessHandler();   // 公开 ← 本插件靠它取回"盖章"（RunConfigurationStamping.kt）
String getDisplayName();              // 公开，但是 UI 文本，多实例会带 "(2)"，不能用来匹配配置名
```

`ExecutionManager.getRunningDescriptors(Condition)` 同样是 `@ApiStatus.Internal`。结论：**"按配置找 Run 内容"没有公开 API**，归属只能自己盖章（见 `src/main/kotlin/.../mcp/RunConfigurationStamping.kt`）。

### `ExecutionManager` 上没有 `stopRunProfile`

Stop 按钮走 `ExecutionManagerImpl.stopProcess`（`impl` 包）。公开可用的组合见 `RunConfigAccess.stopHandler`。

### `ExecutionManager.getExecutors(descriptor)` 是 `@ApiStatus.Internal`

所以本插件不返回 executor 信息。

---

## 2. 怎么核对平台 API 签名

```bash
# 本地已缓存的 IDE 发行版（首次构建后才有）
D=$(ls -d ~/.gradle/caches/9.3.1/transforms/*/transformed/idea-2026.2.3-aarch64)

javap -p -cp "$D/lib/intellij.platform.execution.jar" com.intellij.execution.ui.RunContentDescriptor
javap -p -cp "$D/lib/util-8.jar"                      com.intellij.execution.process.ProcessHandler
```

`ProcessHandler` 在 `util-8.jar` 里（不是 `intellij.platform.execution.jar`），容易找错。

源码 jar（更全，但版本可能滞后一版）：

```bash
ls ~/.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/idea/*/
unzip -p <...>/idea-<版本>-sources.jar com/intellij/execution/ExecutionManager.kt
```

---

## 3. 反编译内置的 MCP Server

`com.intellij.mcpServer` 没上 Marketplace，只能反编译本机安装的那份。

```bash
M="$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins/mcpserver/lib/mcpserver.jar"

# 扩展点清单（在 <extensionPoints> 里）
unzip -p "$M" META-INF/plugin.xml

# 核心接口
javap -p -cp "$M" com.intellij.mcpserver.McpToolsProvider   # getTools(): List<McpTool>
javap -p -cp "$M" com.intellij.mcpserver.McpTool            # descriptor + call(args)
javap -p -cp "$M" com.intellij.mcpserver.McpToolset         # 只有 3 个 default 方法
javap -p -cp "$M" com.intellij.mcpserver.McpCallInfoKt      # coroutineContext.project 等扩展

# 工具是怎么被收集的（决定了你注册的东西会不会被看见）
javap -v -p -cp "$M" com.intellij.mcpserver.impl.McpToolsListProvider
javap -v -p -cp "$M" com.intellij.mcpserver.impl.ReflectionToolsProvider
```

### 结论备忘

- 工具来源 = `McpToolsProvider.EP.extensionList` **加上** `McpToolset.EP` 经反射合成的工具，由
  `McpToolsListProvider.getAllMcpTools()` 合并。两条路都有效。
- `McpSessionHandler` 调用任何工具前都会 `withContext(McpCallAdditionalDataElement(McpCallInfo(...)))`，
  所以**直接实现**的 `McpTool` 里 `coroutineContext.projectOrNull` 同样有效。
- `McpToolSchema.propertiesSchema` 只装 `properties`，不含根级 `"type": "object"`。
- `McpToolsProvider.getTools()` 在 Kotlin 里是**函数**（覆写 `getXxx()` 不会变成 `val`），调用写 `.getTools()`。

---

## 4. 内置 MCP Server 已经有什么、缺什么

`ExecutionToolset`（`com.intellij.mcpserver.toolsets.general`）只有两个工具：

| 工具                        | 说明                                                                           |
| --------------------------- | ------------------------------------------------------------------------------ |
| `get_run_configurations`    | 传 `filePath` 时返回代码里的可执行点；不传时返回项目里的配置列表（静态信息）   |
| `execute_run_configuration` | 启动。参数 `waitForExit=false` 时 `output` 为空，日志要自己读 `fullOutputPath` |

**缺的**（本插件补的）：停止 / 重启 / 运行状态。

`execute_run_configuration` 的两个关键行为：

1. 走的是 **Run** 路径，不是 **Rerun**——目标已在跑时会**再起一个**，不是先停后启
2. 返回值里**没有 PID**（只有 `output` + `fullOutputPath`）。这不是缺陷：进程树是
   `IDEA → sh → pnpm → vite`，隔了好几层，MCP 拿到的可能是中间那层的 handle

---

## 5. 平台集成测试在 2026.2.3 上不可用

```
Cannot create extension (class=Z.Z.Z.Z.Z) [Plugin: com.intellij.modules.ultimate]
  → Cannot find suitable constructor for class Z.Z.Z.Z.Z,
    expected (), (CoroutineScope), (Application), or (Application, CoroutineScope)
```

- `Z.Z.Z.Z.Z` 是 `lib/product-backend.jar` 里的平台内部接口（混淆名），一个 `interface`
- 失败发生在打开测试项目的 post-startup 阶段（`StartupManagerImpl.runPostStartupActivities`）
- 与本插件无关：上游 `syllr/copy-as-prompt` 指向 2026.2.3 失败完全一致
- 试过且**无效**的手段：去掉 `com.intellij.java` bundled plugin、加
  `systemProperty("idea.force.use.core.classloader", "true")`、换 `intellijIdeaCommunity`
  （2026.2 已停发 `ideaIC`，根本下不到）

---

## 6. Gradle 侧的两个硬约束

### IntelliJ Platform Gradle Plugin 必须 ≥ 2.19.0

2.14.0 解析不了 2026.2 发行版 `modules/module-descriptors.jar` 里的 `$legacy_jps_library` 描述符：

```
Invalid XML value at position: 563:4: Field 'name' is required for type with serial name
'org.jetbrains.intellij.platform.gradle.models.ModuleDescriptor', but it was missing
```

连 `:test` 的依赖都算不出来。

### bundled plugin 的 ID 大小写敏感

`com.intellij.mcpServer`（大写 S）写成 `com.intellij.mcpserver` 会报：

```
Could not find bundled plugin with ID: 'com.intellij.mcpserver'
```

---

## 7. 官方文档

- 插件开发：https://plugins.jetbrains.com/docs/intellij/
- 扩展点：https://plugins.jetbrains.com/docs/intellij/plugin-extension-points.html
- 可选依赖：https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html#option_2_dependency
