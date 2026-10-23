package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.eel.provider.EelProviderUtil
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.ui.content.Content
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.terminal.TerminalEngine
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import org.jetbrains.plugins.terminal.startup.TerminalProcessType
import org.jetbrains.plugins.terminal.view.TerminalOffset
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandBlock
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandExecutionListener
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandFinishedEvent
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地终端的平台接线层：预检、建真终端 tab、发命令、读 / 写。
 *
 * 这是**唯一** import `com.intellij.terminal.frontend.*` / `org.jetbrains.plugins.terminal.*`
 * 的地方（远程终端在 `RemoteTerminalAccess`）。
 */

/** 预检失败 / 运行期失败 → 工具层转成结构化错误。 */
internal class LocalTerminalHandle(
    val view: TerminalView,
    val content: Content,
)

internal class TerminalAccessException(
    val code: String,
    override val message: String,
    val current: String? = null,
    val fix: List<String> = emptyList(),
) : RuntimeException(message)

/**
 * 每个 tab 的会话状态。
 *
 * 用 project service 存（不是 `object` 里的 `var`），生命周期跟项目一致；
 * `uiHandle` 是本地 `TerminalView` 或远程 `TerminalWidget`，用 `Any` 是为了让这个类
 * 不依赖任何终端插件的类型，两个 config-file 的类加载器都能用。
 */
internal class TerminalSessionRegistry {

    internal class State(
        var uiHandle: Any? = null,
        /** 本地增量读的游标（TerminalOutputModel 的绝对 offset）。 */
        var lastReadOffset: Long = 0L,
        /** 远程增量读 / 命令段的上次全文快照。 */
        var lastText: String = "",
        /** 远程命令段：每条命令发送前的文本快照。 */
        val markers: MutableList<TerminalCommandMarker> = mutableListOf(),
        /** 本地上一次看到的有效 command block 数；用于检测 clear/reset 把历史清掉。 */
        var lastBlockCount: Int = 0,
        /** tab 来源：MCP 本地 / MCP 远程。 */
        val kind: TerminalKind = TerminalKind.ProjectSdk,
        val createdAt: Long = System.currentTimeMillis(),
        var workingDirectory: String? = null,
        var serverName: String? = null,
        var shell: String? = null,
        var lastCommand: String? = null,
        var lastCommandAt: Long? = null,
        var lastExitCode: Int? = null,
        var lastTimedOut: Boolean? = null,
        var lastRunning: Boolean? = null,
        var lastUsedAt: Long? = null,
    )

    private val states = ConcurrentHashMap<String, State>()
    private val counter = AtomicInteger(0)

    /** 生成当前项目里尚未使用的 tab 名；避免 IDE 重启后与恢复的旧 tab 重名。 */
    fun nextTabName(prefix: String, isTaken: (String) -> Boolean = { false }): String {
        while (true) {
            val name = "$prefix-${counter.incrementAndGet()}"
            if (!isTaken(name) && !states.containsKey(name)) return name
        }
    }

    fun get(tab: String): State? = states[tab]

    /** 当前 MCP 管理的 tab 快照；返回副本，避免调用方遍历时被增删影响。 */
    fun snapshot(): Map<String, State> = states.toMap()

    fun put(tab: String, state: State) {
        states[tab] = state
    }

    fun remove(tab: String) {
        states.remove(tab)
    }
}

// ---------------------------------------------------------------------------
// 预检
// ---------------------------------------------------------------------------

internal fun preflightLocalTerminal(project: Project): TerminalPreflight.Blocked? {
    if (ToolWindowManager.getInstance(project).getToolWindow("Terminal") == null) {
        return TerminalPreflight.Blocked(
            code = "terminal_tool_window_missing",
            message = """
                终端不可用：Terminal 工具窗不存在，Terminal 插件可能被禁用。

                修复步骤：
                1. 打开 Settings | Plugins
                2. 启用 "Terminal" 插件
                3. 重启 IDE 后重试
            """.trimIndent(),
        )
    }

    val options = service<TerminalOptionsProvider>()
    if (options.terminalEngine != TerminalEngine.REWORKED) {
        return TerminalPreflight.Blocked(
            code = "terminal_engine_not_reworked",
            message = """
                终端不可用：当前 Terminal engine 是 "${options.terminalEngine.presentableName}"，本工具需要 "Reworked 2025"。

                修复步骤：
                1. 打开 Settings | Tools | Terminal
                2. 把 "Terminal engine" 设为 "Reworked 2025"
                3. 点 Apply，然后重试
            """.trimIndent(),
            current = "terminalEngine=${options.terminalEngine}",
            fix = listOf(
                "Settings | Tools | Terminal",
                "Terminal engine: Reworked 2025",
            ),
        )
    }

    if (!options.shellIntegration) {
        return TerminalPreflight.Blocked(
            code = "shell_integration_disabled",
            message = """
                终端不可用：Shell integration 未开启，本工具靠它识别命令的开始/结束并抓取输出。

                修复步骤：
                1. 打开 Settings | Tools | Terminal
                2. 勾选 "Shell integration"
                3. 点 Apply，然后重试
            """.trimIndent(),
            current = "shellIntegration=false",
            fix = listOf(
                "Settings | Tools | Terminal",
                "Shell integration: checked",
            ),
        )
    }

    val eel = EelProviderUtil.getEelDescriptor(project)
    if (eel != LocalEelDescriptor) {
        return TerminalPreflight.Blocked(
            code = "remote_project_not_supported",
            message = """
                终端不可用：当前项目运行在远程开发模式，本地终端 API 不可用。

                请改用 execute_in_remote_host 在远程主机上执行。
            """.trimIndent(),
            current = "eelDescriptor=$eel",
            fix = listOf("使用 execute_in_remote_host"),
        )
    }

    return null
}

// ---------------------------------------------------------------------------
// 本地：执行
// ---------------------------------------------------------------------------

internal suspend fun runInLocalTerminal(
    project: Project,
    command: String,
    input: String?,
    timeoutMs: Int,
    workingDirectory: String?,
    env: Map<String, String>,
    maxChars: Int = TERMINAL_DEFAULT_MAX_CHARS,
): TerminalRunResult {
    preflightLocalTerminal(project)?.let { throw TerminalAccessException(it.code, it.message, it.current, it.fix) }

    val registry = project.service<TerminalSessionRegistry>()
    val aliveNames = aliveTerminalTabNames(project)
    val tabName = registry.nextTabName("MCP", aliveNames::contains)
    // 项目级工具环境（Maven home / PATH）；用户显式 env 覆盖自动值
    val mergedEnv = project.service<ProjectTerminalEnvRegistry>().extraEnv(project) + env
    val resolvedWorkingDirectory = expandHomeDirectory(
        workingDirectory,
        System.getProperty("user.home"),
        File.separatorChar,
    ) ?: project.basePath

    val tab = withContext(Dispatchers.EDT) {
        TerminalToolWindowTabsManager.getInstance(project)
            .createTabBuilder()
            .tabName(tabName)
            .workingDirectory(resolvedWorkingDirectory)
            .processType(TerminalProcessType.SHELL)
            .requestFocus(false)
            .deferSessionStartUntilUiShown(false)
            .closeOnProcessTermination(false)
            .apply { if (mergedEnv.isNotEmpty()) envVariables(mergedEnv) }
            .createTab()
    }

    val view = tab.view
    val state = TerminalSessionRegistry.State(
        uiHandle = LocalTerminalHandle(view, tab.content),
        kind = TerminalKind.ProjectSdk,
        workingDirectory = resolvedWorkingDirectory,
    )
    registry.put(tabName, state)

    val running = withTimeoutOrNull(10_000) {
        view.sessionState.first { it is TerminalViewSessionState.Running }
    }
    if (running == null) {
        throw TerminalAccessException(
            code = "terminal_session_not_started",
            message = """
                终端已打开，但 shell 在 10 秒内没有启动，命令未执行。

                请检查 Settings | Tools | Terminal 里的 shell path，或先在 IDE 里手动打开一个终端确认能否启动。
            """.trimIndent(),
        )
    }

    // Windows 的 CMD 没有 command block / shell integration；快速失败，不要等 shell integration 超时。
    val startup = withTimeoutOrNull(2_000) { view.startupOptionsDeferred.await() }
    state.shell = startup?.shellCommand?.firstOrNull()?.let { File(it).name }
    if (startup != null && isCmdShell(startup.shellCommand)) {
        registry.remove(tabName)
        withContext(Dispatchers.EDT) {
            TerminalToolWindowTabsManager.getInstance(project).closeTab(tab)
        }
        throw TerminalAccessException(
            code = "local_shell_not_supported",
            message = "本地终端不支持 CMD。请把 Settings | Tools | Terminal 的 Shell path 改成 PowerShell 或 Git Bash，然后重试。",
            current = "shell=${startup.shellCommand.firstOrNull()}",
            fix = listOf("Settings | Tools | Terminal", "Shell path: PowerShell / Git Bash"),
        )
    }

    val integration = withTimeoutOrNull(10_000) { view.shellIntegrationDeferred.await() }
        ?: throw TerminalAccessException(
            code = "shell_integration_not_started",
            message = """
                终端已打开，但 Shell integration 在 10 秒内没有就绪，命令未执行。

                可能原因：
                - 当前 shell 不受支持（支持 bash / zsh / fish / PowerShell；不支持 CMD）
                - shell 启动脚本里的错误中断了 shell integration

                修复步骤：
                1. 确认 Settings | Tools | Terminal 里 "Shell integration" 已勾选
                2. 在 IDE 终端里新开一个 tab，看是否能正常出现命令块
                3. 重试
            """.trimIndent(),
        )

    withTimeoutOrNull(10_000) {
        integration.outputStatus.first { it is TerminalOutputStatus.WaitingForPrompt }
    } ?: throw TerminalAccessException(
        code = "terminal_prompt_not_ready",
        message = "终端已打开，但 10 秒内没有出现提示符，命令未执行。请在 IDE 终端里确认 shell 能正常启动。",
    )

    val finished = CompletableDeferred<CommandFinished>()
    val disposable = Disposer.newDisposable()
    integration.addCommandExecutionListener(
        disposable,
        object : TerminalCommandExecutionListener {
            override fun commandFinished(event: TerminalCommandFinishedEvent) {
                val block = event.commandBlock
                val model = event.outputModel
                val start = block.outputStartOffset ?: block.commandStartOffset ?: block.startOffset
                val from = maxOf(start, model.startOffset)
                val raw = runCatching { model.getText(from, block.endOffset).toString() }.getOrDefault("")
                finished.complete(CommandFinished(raw, block.exitCode, from > start))
            }
        },
    )

    fun record(result: TerminalRunResult): TerminalRunResult {
        state.lastExitCode = result.exitCode
        state.lastTimedOut = result.timedOut
        state.lastRunning = result.running
        state.lastUsedAt = System.currentTimeMillis()
        return result
    }

    val commandStartedAt = System.currentTimeMillis()
    state.lastCommand = command
    state.lastCommandAt = commandStartedAt
    state.lastUsedAt = commandStartedAt

    try {
        val model = view.outputModels.active.value
        val beforeEnd = model.endOffset

        withContext(Dispatchers.EDT) {
            view.createSendTextBuilder().shouldExecute().send(command)
        }
        if (!input.isNullOrEmpty()) {
            withContext(Dispatchers.EDT) { view.sendText(input) }
        }

        if (timeoutMs <= 0) {
            // 让命令回显 / 提示符先出现，异步快照才有意义
            delay(200)
            // 从 command block 的输出起点读，避免首帧带上半截命令回显
            val active = integration.blocksModel.activeBlock as? TerminalCommandBlock
            val start = active?.outputStartOffset ?: beforeEnd
            val from = maxOf(start, model.startOffset)
            val raw = runCatching { model.getText(from, model.endOffset).toString() }.getOrDefault("")
            val (text, truncated) = cleanTerminalText(raw, maxChars)
            state.lastReadOffset = model.endOffset.toAbsolute()
            return record(
                TerminalRunResult(
                    tab = tabName,
                    text = text,
                    exitCode = null,
                    timedOut = false,
                    running = integration.outputStatus.value is TerminalOutputStatus.ExecutingCommand,
                    truncated = truncated,
                    warning = shellChangeWarning(command),
                ),
            )
        }

        val result = withTimeoutOrNull(timeoutMs.toLong()) { finished.await() }
        if (result == null) {
            val active = integration.blocksModel.activeBlock as? TerminalCommandBlock
            val start = active?.outputStartOffset ?: beforeEnd
            val from = maxOf(start, model.startOffset)
            val raw = runCatching { model.getText(from, model.endOffset).toString() }.getOrDefault("")
            val (text, truncated) = cleanTerminalText(raw, maxChars)
            state.lastReadOffset = model.endOffset.toAbsolute()
            return record(
                TerminalRunResult(
                    tab = tabName,
                    text = text,
                    exitCode = null,
                    timedOut = true,
                    running = true,
                    truncated = truncated || from > start,
                    warning = shellChangeWarning(command),
                ),
            )
        }

        val (text, truncated) = cleanTerminalText(result.text, maxChars)
        state.lastReadOffset = model.endOffset.toAbsolute()
        return record(
            TerminalRunResult(
                tab = tabName,
                text = text,
                exitCode = result.exitCode,
                timedOut = false,
                running = false,
                truncated = truncated || result.truncated,
                warning = shellChangeWarning(command),
            ),
        )
    } finally {
        state.lastBlockCount = integration.blocksModel.blocks
            .filterIsInstance<TerminalCommandBlock>()
            .count { !it.executedCommand.isNullOrBlank() }
        Disposer.dispose(disposable)
    }
}

private data class CommandFinished(
    val text: String,
    val exitCode: Int?,
    val truncated: Boolean,
)

// ---------------------------------------------------------------------------
// 本地：读 / 写
// ---------------------------------------------------------------------------

/** 校验本地 tab 仍然存在；用户点 X 关掉 tab 后 content 会 dispose。 */
private fun requireLocalHandle(
    project: Project,
    tab: String,
    state: TerminalSessionRegistry.State,
): LocalTerminalHandle {
    val handle = state.uiHandle as? LocalTerminalHandle
        ?: throw TerminalAccessException(
            code = "terminal_tab_not_local",
            message = "tab \"$tab\" 不是本地终端。远程终端请用 target=\"remote\"。",
        )
    if (Disposer.isDisposed(handle.content) || !handle.content.isValid) {
        project.service<TerminalSessionRegistry>().remove(tab)
        throw TerminalAccessException(
            code = "terminal_closed",
            message = "本地终端 \"$tab\" 已被关闭，无法继续读取/写入。请重新 project_sdk_terminal 创建新 tab。",
        )
    }
    return handle
}

internal suspend fun readLocalTerminal(
    project: Project,
    tab: String,
    tail: Int?,
    full: Boolean,
    timeoutMs: Int,
    maxChars: Int = TERMINAL_DEFAULT_MAX_CHARS,
): TerminalReadResult {
    val state = project.service<TerminalSessionRegistry>().get(tab)
        ?: throw TerminalAccessException(
            code = "terminal_tab_not_found",
            message = "找不到终端 tab \"$tab\"。请使用 project_sdk_terminal 返回的 tab id。",
        )
    val view = requireLocalHandle(project, tab, state).view
    state.lastUsedAt = System.currentTimeMillis()

    val integration = runCatching { view.shellIntegrationDeferred.await() }.getOrNull()
        ?: throw TerminalAccessException(
            code = "shell_integration_not_started",
            message = "终端 \"$tab\" 的 Shell integration 没有就绪，无法读取命令段。",
        )

    val model = view.outputModels.active.value
    if (timeoutMs > 0) waitForStable(model, timeoutMs)

    val running = integration.outputStatus.value is TerminalOutputStatus.ExecutingCommand
    state.lastRunning = running

    if (full) {
        val (text, truncated) = cleanTerminalText(model.getText(model.startOffset, model.endOffset).toString(), maxChars)
        state.lastReadOffset = model.endOffset.toAbsolute()
        return TerminalReadResult(tab, text, null, running, truncated)
    }

    if (tail != null) {
        val blocks = integration.blocksModel.blocks
            .filterIsInstance<TerminalCommandBlock>()
            .filter { !it.executedCommand.isNullOrBlank() }
        val historyLost = blocks.size < state.lastBlockCount
        state.lastBlockCount = blocks.size
        val selected = if (tail >= blocks.size) blocks else blocks.takeLast(tail.coerceAtLeast(1))
        var truncated = false
        val allSegments = selected.map { block ->
            val start = block.outputStartOffset ?: block.commandStartOffset ?: block.startOffset
            val from = maxOf(start, model.startOffset)
            if (from > start) truncated = true
            val raw = runCatching { model.getText(from, block.endOffset).toString() }.getOrDefault("")
            val (output, cut) = cleanTerminalText(raw, maxChars)
            if (cut) truncated = true
            TerminalCommandSegment(
                command = block.executedCommand.orEmpty(),
                output = output,
                exitCode = block.exitCode,
            )
        }
        val (segments, totalCut) = capSegments(allSegments, maxChars)
        state.lastReadOffset = model.endOffset.toAbsolute()
        return TerminalReadResult(tab, null, segments, running, truncated || totalCut || historyLost)
    }

    val modelStart = model.startOffset.toAbsolute()
    val modelEnd = model.endOffset.toAbsolute()
    // clear / reset 会把 buffer 重置到更小的 offset；此时从新 buffer 起点读，不能沿用旧 offset
    val bufferReset = state.lastReadOffset > modelEnd
    val fromAbsolute = if (bufferReset) modelStart else maxOf(state.lastReadOffset, modelStart)
    val from = TerminalOffset.of(fromAbsolute)
    val truncatedByScrollback = state.lastReadOffset > 0 && state.lastReadOffset < modelStart
    val raw = runCatching { model.getText(from, model.endOffset).toString() }.getOrDefault("")
    state.lastReadOffset = modelEnd
    val (text, truncated) = cleanTerminalText(raw, maxChars)
    return TerminalReadResult(tab, text, null, running, truncated || truncatedByScrollback || bufferReset)
}

internal suspend fun writeLocalTerminal(project: Project, tab: String, text: String) {
    val state = project.service<TerminalSessionRegistry>().get(tab)
        ?: throw TerminalAccessException(
            code = "terminal_tab_not_found",
            message = "找不到终端 tab \"$tab\"。请使用 project_sdk_terminal 返回的 tab id。",
        )
    val view = requireLocalHandle(project, tab, state).view
    state.lastUsedAt = System.currentTimeMillis()
    try {
        withContext(Dispatchers.EDT) { view.sendText(text) }
    } catch (e: Throwable) {
        throw TerminalAccessException(
            code = "terminal_closed",
            message = "本地终端 \"$tab\" 已退出（${e.message ?: "closed"}）。请重新 project_sdk_terminal 创建新 tab。",
            current = e.message,
        )
    }
}

/** 轮询到输出稳定（500ms 没变化）或超时。 */
private suspend fun waitForStable(model: TerminalOutputModel, timeoutMs: Int) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = model.modificationStamp
    var stableSince = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
        delay(100)
        val now = model.modificationStamp
        if (now != last) {
            last = now
            stableSince = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - stableSince >= 500) {
            return
        }
    }
}
