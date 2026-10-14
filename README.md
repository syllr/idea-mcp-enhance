# IDE MCP Enhance

把 IDE 里的信息变成 prompt 文本，并给 JetBrains 内置的 MCP Server 补上 Run Configuration 的生命周期能力。

<!-- Plugin description -->

| 能力                           | 入口                | 作用                                                                            |
| ------------------------------ | ------------------- | ------------------------------------------------------------------------------- |
| **Copy as Prompt**             | 编辑器右键          | 把选中的代码带上文件路径与行号复制到剪贴板                                      |
| **Copy Diagnostics as Prompt** | 编辑器右键          | 把当前文件的编译错误 / 警告整理成清单复制到剪贴板                               |
| **Run Lifecycle MCP 工具**     | MCP（Open Code 等） | 列出 / 查询 / 停止 / 重启 Run Configuration——补齐内置 MCP Server 缺的那部分能力 |

<!-- Plugin description end -->

## 为什么不只是 Copy as Prompt

JetBrains 2026.2 内置的 MCP Server（`com.intellij.mcpServer`）已经提供了 `execute_run_configuration`，但**只有"启动"**：

| 能力       | IDEA 界面 | 内置 MCP Server | 本插件 |
| ---------- | --------- | --------------- | ------ |
| 运行       | ✓         | ✓               | —      |
| 停止       | ✓         | ✗               | ✓      |
| 重启 Rerun | ✓         | ✗               | ✓      |
| 运行状态   | ✓         | ✗               | ✓      |

缺了"停止"，"重启"就只能是 stop + start 两步拼出来；而内置的 `execute_run_configuration` 每次调用都是**无脑 spawn 一个新进程**——同一个配置连调两次会起两个实例，端口从 5173 漂到 5174，IDEA 那边的 Run 窗口标签页还显示着第一次那个"在跑"的实例，状态完全对不上。

本插件把 `ProcessHandler` 这层拿到手之后，"停"和"重启"就是 IDE 内部本来就有的两个动作，不需要猜端口、不需要匹配命令行、不需要改项目的启动命令。

## Run Lifecycle MCP 工具

前提：目标 IDE 为 2026.2+，且内置的 **MCP Server** 插件已启用（Settings → Tools → MCP Server）。没启用时本插件的三个工具不会出现在工具列表里，prompt 功能不受影响。

| 工具                          | 类型   | 作用                                                                                                               |
| ----------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------ |
| `list_running_configurations` | 只读   | 只列**正在跑**的配置及其**活实例**（实例数、PID、executionId、是否正在终止）；没有在跑的就返回空                   |
| `stop_run_configuration`      | 破坏性 | 停掉该配置的**所有**实例（修掉"起两个实例"的老问题），可等待其真正退出                                             |
| `rerun_run_configuration`     | 破坏性 | 等价于 IDE 的 Rerun 按钮：**在跑就重启，没在跑就启动**；同步等到新进程真的起来，并回传启动日志、会话标识与状态流转 |

MCP 客户端配置（与 IDE 官方文档一致）：

```json
{
  "type": "streamable-http",
  "url": "http://127.0.0.1:<MCP Server 的端口>/stream",
  "headers": { "IJ_MCP_SERVER_PROJECT_PATH": "<项目根目录的绝对路径>" }
}
```

这个 header 不能省：MCP Server 在派发工具之前要先解析"针对哪个项目"，它只认这个 header 或调用参数 `projectPath`
（**只打开一个项目是不够的**）。三个工具都声明了可选的 `projectPath`，所以没配 header 的客户端也能用，
但每次调用都得带上它。

设计约束：

- **语言无关** —— 走 IDE 的 `ProcessHandler`，不依赖命令行模式匹配，也不依赖应用监听某个固定端口
- **不改项目** —— 用户的 `package.json` / `pom.xml` / 启动命令一个字都不用动
- **显式** —— 不做任何隐藏操作；`stop` / `rerun` 都是用户或 agent 明确调用才执行

## Copy as Prompt

### 1. 复制选中代码（Copy as Prompt）

1. 在编辑器中**选中**一段代码（单行或多行均可）
2. **右键** → **Copy as Prompt**
3. 内容已进剪贴板，编辑器选区自动取消
4. 到任意 LLM 对话界面 Cmd/Ctrl+V

输出示例（选中 `src/main/kotlin/Foo.kt` 第 10-20 行）：

````
location:src/main/kotlin/Foo.kt:10-20
content:
```kotlin
val a = 1
val b = 2
````

```

### 2. 复制当前文件的错误与警告（Copy Diagnostics as Prompt）

1. 把光标放在任意位置（**不需要选中**任何东西）
2. **右键** → **Copy Diagnostics as Prompt**

输出示例：

```

location:src/main/kotlin/Foo.kt
diagnostics:

- [ERROR] line 12: Cannot resolve method 'foo'
  foo();
- [WARNING] line 14: 'x' is never used
  val x = 1

````

> **菜单项是灰的？** 说明当前文件没有 error / warning（这也是它在告诉你"这个文件没问题"）。**Copy as Prompt** 则是没选中非空白文本时为灰。

## 配置

**无需任何配置。** 插件不读写配置文件、不读取环境变量。MCP 工具的开关跟随 IDE 内置 MCP Server 的设置。

## 故障排除

**问题：右键菜单里找不到这两个 action**

- 确认插件已启用（Settings/Preferences → Plugins）
- 确认焦点在**编辑器**内（这两个 action 只出现在编辑器右键菜单）

**问题：MCP 工具列表里看不到 Run Lifecycle 那三个**

- 确认 IDE 是 2026.2+（本插件 `sinceBuild=262`，因为要编译内置 MCP Server 的 API）
- 确认内置的 **MCP Server** 插件已安装并启用
- 打开 MCP Server 的工具面板，确认没有在 Allowed tools 里把它们过滤掉

**问题：`stop_run_configuration` 报"找不到运行中的实例"**

- 该配置确实没在跑。`rerun_run_configuration` 在这种情况下会直接启动它（这正是 Rerun 的语义）

**问题：诊断只列出了 part 的问题 / 少了几条**

- 插件只采集 daemon 已经算出的高亮。如果某个文件刚打开、后台分析还没跑完，可能还没生成全部诊断——稍等片刻或让编辑器获得焦点后再复制
- 插件只输出 **ERROR / WARNING** 两档（不含 INFO、弱警告、拼写提示等）

## 开发

```bash
./gradlew test                     # 单元测试（纯函数 + 平台集成）
./gradlew runIde                   # 启动带插件的 IDE
./gradlew buildPlugin              # 构建插件 zip
````

目录结构、强制约束与平台 API 踩坑见 [`AGENTS.md`](./AGENTS.md)；行为契约见 [`SPEC.md`](./SPEC.md)；架构与关键决策见 [`DESIGN.md`](./DESIGN.md)。

## 兼容性

| 项       | 约束                                                               |
| -------- | ------------------------------------------------------------------ |
| IDE      | **2026.2（build 262）及以上**——MCP 增强依赖这一版的内置 MCP Server |
| 其他 IDE | 带 `com.intellij.modules.platform` 的都支持（prompt 功能）         |
| JDK      | 21（仅构建需要，运行在 IDE 自带的 JBR 上）                         |

> ⚠️ 与上游 [`syllr/copy-as-prompt`](https://github.com/syllr/copy-as-prompt) 的关系：prompt 部分的实现与输出格式契约来自那个仓库，保持逐字节一致；本仓库额外加入了 MCP 增强，并把目标平台提到 2026.2。

## 许可证

本项目采用 MIT 许可证。

## 免责声明

本插件是社区维护的非官方插件。使用本插件产生的任何问题，请通过 GitHub Issues 反馈。
