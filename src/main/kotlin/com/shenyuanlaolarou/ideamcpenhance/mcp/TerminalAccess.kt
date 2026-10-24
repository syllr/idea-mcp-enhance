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
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration
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
        /** 这条命令还没跑完：复用选 tab 时跳过，避免两条命令挤进同一个 shell。 */
        var busy: Boolean = false,
    )

    private val states = ConcurrentHashMap<String, State>()
    /** 每个前缀各自的序号：`mcp-<主机>-1` 从 1 起，不和别的机器共用计数器。 */
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 每种来源的默认 tab：key = `project_sdk` / `remote:<服务器名>`。
     *
     * "默认"只由**自动创建**的那条 tab 取得（不传 `tab`、不传 `newTab`、不带 `env`）。
     * `newTab` 建出来的第二条不抢这个身份，所以默认值不会随使用历史漂移。
     */
    private val defaultTabs = ConcurrentHashMap<String, String>()

    /** 一种来源的 key：本地固定一个，远程按服务器各一个。 */
    fun defaultKey(kind: TerminalKind, serverName: String?): String =
        if (kind == TerminalKind.Remote) "remote:${serverName.orEmpty()}" else kind.wire

    /** 该来源当前的默认 tab；不存在或已关闭时返回 null（调用方据此新建）。 */
    fun defaultTab(kind: TerminalKind, serverName: String? = null): String? {
        val tab = defaultTabs[defaultKey(kind, serverName)] ?: return null
        return if (states.containsKey(tab)) tab else null
    }

    /** 登记默认 tab；已经有默认时不动（默认只在"没有"时才建立）。 */
    fun registerDefault(tab: String, kind: TerminalKind, serverName: String? = null) {
        defaultTabs.putIfAbsent(defaultKey(kind, serverName), tab)
    }

    /** 生成当前项目里尚未使用的 tab 名；避免 IDE 重启后与恢复的旧 tab 重名。 */
    fun nextTabName(prefix: String, isTaken: (String) -> Boolean = { false }): String {
        val counter = counters.computeIfAbsent(prefix) { AtomicInteger(0) }
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
        // 关掉的可能正是默认 tab：一并清掉，下次调用会重建一条新的默认。
        defaultTabs.entries.removeIf { it.value == tab }
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
    tab: String? = null,
    newTab: Boolean = false,
): TerminalRunResult {
    val registry = project.service<TerminalSessionRegistry>()

    // 不传 tab 时用本项目的那条**默认** tab（不是"最近用过的"）：落点可预测，
    // 连续命令永远落在同一条 shell 上。带 env 的调用不复用 —— env 只在新 tab
    // 建的时候生效，复用会静默丢掉它。
    val autoCreate = tab == null && !newTab && env.isEmpty()
    val reuseTarget = tab
        ?: if (!autoCreate) null else registry.defaultTab(TerminalKind.ProjectSdk)
    if (reuseTarget != null) {
        val state = registry.get(reuseTarget)
        // 默认 tab 可能已经被用户关掉（registry.remove 也会清掉默认登记）：当作没有，
        // 直接新开一条；但如果是调用方点名的 tab，关掉了要明确报错，不能偷偷换一条。
        val view = state?.let { runCatching { requireLocalHandle(project, reuseTarget, it).view }.getOrNull() }
        if (view != null) {
            return executeLocalCommand(
                view, state, reuseTarget, workingDirectory, command, input, timeoutMs, maxChars,
            )
        }
        if (tab != null) {
            throw TerminalAccessException(
                code = "terminal_tab_not_found",
                message = "终端 tab \"$tab\" 不存在或已被关闭。请传 newTab=true 新开一个，或用 list_terminals 查现有 tab。",
            )
        }
    }

    preflightLocalTerminal(project)?.let { throw TerminalAccessException(it.code, it.message, it.current, it.fix) }

    val aliveNames = aliveTerminalTabNames(project)
    val tabName = registry.nextTabName("MCP", aliveNames::contains)
    // 项目级工具环境（Maven home / PATH）；用户显式 env 覆盖自动值
    val mergedEnv = project.service<ProjectTerminalEnvRegistry>().extraEnv(project) + env
    val resolvedWorkingDirectory = expandHomeDirectory(
        workingDirectory,
        System.getProperty("user.home"),
        File.separatorChar,
    ) ?: project.basePath

    val createdTab = withContext(Dispatchers.EDT) {
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

    val view = createdTab.view
    val state = TerminalSessionRegistry.State(
        uiHandle = LocalTerminalHandle(view, createdTab.content),
        kind = TerminalKind.ProjectSdk,
        workingDirectory = resolvedWorkingDirectory,
    )
    registry.put(tabName, state)
    // 只有"自动创建"的那条才成为默认；newTab / env 建出来的是临时 shell，不抢这个身份。
    if (autoCreate) registry.registerDefault(tabName, TerminalKind.ProjectSdk)

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
            TerminalToolWindowTabsManager.getInstance(project).closeTab(createdTab)
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

    // 刚建的 tab 已经站在 resolvedWorkingDirectory 里，不用再 cd 一次。
    return executeLocalCommand(view, state, tabName, null, command, input, timeoutMs, maxChars)
}

/**
 * 在一个**已经就绪**的本地 tab 里跑一条命令并读回结果。
 *
 * 建 tab 与复用走的是同一段逻辑：命令块监听、超时、读输出都只有这一份实现。
 */
private suspend fun executeLocalCommand(
    view: TerminalView,
    state: TerminalSessionRegistry.State,
    tabName: String,
    workingDirectory: String?,
    command: String,
    input: String?,
    timeoutMs: Int,
    maxChars: Int,
): TerminalRunResult {
    val integration = withTimeoutOrNull(10_000) { view.shellIntegrationDeferred.await() }
        ?: throw TerminalAccessException(
            code = "shell_integration_not_started",
            message = "终端 \"$tabName\" 的 Shell integration 没有就绪，命令未执行。",
        )

    // 复用的 tab 可能还在跑上一条命令（也可能只是 shell integration 晚了约 200ms
    // 才翻状态）：一直退避等到它回到提示符，不设上限 —— tab 真关掉了才放弃。
    if (!awaitLocalIdle(integration)) {
        throw TerminalAccessException(
            code = "terminal_tab_busy",
            message = "终端 \"$tabName\" 在等待期间被关闭，本次命令没有发送。请重新 project_sdk_terminal 开一个 tab。",
        )
    }

    // 复用时不重开 shell：只把目录切过去，其余状态（export、后台进程）原样保留。
    // 没传 workingDirectory 就沿用这个 shell 当前所在目录。
    val sent = if (workingDirectory.isNullOrBlank()) {
        command
    } else {
        "cd ${posixQuote(workingDirectory)} && $command"
    }

    state.busy = true
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
            view.createSendTextBuilder().shouldExecute().send(sent)
        }
        if (!input.isNullOrEmpty()) {
            withContext(Dispatchers.EDT) { view.sendText(input) }
        }

        val result = withTimeoutOrNull(timeoutMs.toLong()) { finished.await() }
        if (result == null) {
            val active = integration.blocksModel.activeBlock as? TerminalCommandBlock
            val start = active?.outputStartOffset ?: beforeEnd
            val from = maxOf(start, model.startOffset)
            val raw = runCatching { model.getText(from, model.endOffset).toString() }.getOrDefault("")
            val (text, truncated) = cleanTerminalText(raw, maxChars)
            // 不推进 lastReadOffset：它只属于 terminal_read 的增量游标。这里的 model 是命令
            // 结束时的那个，和 read 时的 active model 未必同一套坐标；推进会让下一次读误判成
            // buffer reset，把整段历史重放一遍。
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
        // 同上：执行路径不碰读游标。
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
        state.busy = false
        state.lastBlockCount = integration.blocksModel.blocks
            .filterIsInstance<TerminalCommandBlock>()
            .count { !it.executedCommand.isNullOrBlank() }
        Disposer.dispose(disposable)
    }
}

/**
 * 退避等本地 shell 回到提示符，**不设上限**。
 *
 * 序列按 [retryBackoffMs]：250ms、500ms，之后每次 1000ms。只有读不到终端状态
 * （tab 被关掉）时才返回 false —— 那已经不是"忙"，是真没了。
 *
 * 无限等待成立的前提是客户端按 Streamable HTTP 规范挂着 `GET /stream` 的 SSE 流
 * （OpenCode 这类标准客户端就是）：挂了流会话一直活着，实测 100 秒的调用正常返回。
 * 只发 POST 不挂流的裸客户端会被 IDE 当 pending transport，15 秒后清掉会话，
 * 那种情况下长调用会拿到 404 —— 这是客户端问题，不是这里该退让的理由。
 */
private suspend fun awaitLocalIdle(integration: TerminalShellIntegration): Boolean {
    var attempt = 0
    while (true) {
        val busy = runCatching {
            integration.outputStatus.value is TerminalOutputStatus.ExecutingCommand
        }.getOrElse { return false }
        if (!busy) return true
        delay(retryBackoffMs(attempt++))
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

/**
 * 往本地终端发一次 Ctrl-C。
 *
 * 这是唯一能在命令**还跑着**时生效的写入：执行工具发送前会先等 tab 空闲，卡住的命令
 * 会把它们永远挡在门外。只发一个字节，不接受文本，所以它不能变成执行通道。
 */
internal suspend fun interruptLocalTerminal(project: Project, tab: String) {
    val state = project.service<TerminalSessionRegistry>().get(tab)
        ?: throw TerminalAccessException(
            code = "terminal_tab_not_found",
            message = "找不到终端 tab \"$tab\"。请使用 project_sdk_terminal 返回的 tab id。",
        )
    val view = requireLocalHandle(project, tab, state).view
    state.lastUsedAt = System.currentTimeMillis()
    try {
        withContext(Dispatchers.EDT) { view.sendText(TERMINAL_INTERRUPT_CHAR) }
    } catch (e: Throwable) {
        throw TerminalAccessException(
            code = "terminal_closed",
            message = "本地终端 \"$tab\" 已退出（${e.message ?: "closed"}）。请重新 project_sdk_terminal 创建新 tab。",
            current = e.message,
        )
    }
}

