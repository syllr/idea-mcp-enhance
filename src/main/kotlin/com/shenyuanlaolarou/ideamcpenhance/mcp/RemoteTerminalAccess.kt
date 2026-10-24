package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.remote.AuthType
import com.intellij.remote.RemoteCredentialsHolder
import com.intellij.remote.SshConnectionConfigPatch.HostKeyVerifier.StrictHostKeyChecking
import com.intellij.ssh.ConnectionBuilder
import com.intellij.ssh.OpenSshLikeHostKeyVerifier
import com.intellij.ssh.config.AuthMethod
import com.intellij.ssh.config.AuthMethods
import com.intellij.ssh.interaction.PlatformSshPasswordProvider
import com.intellij.terminal.ui.TerminalWidget
import com.jetbrains.plugins.remotesdk.console.SshTerminalCachingRunner
import com.jetbrains.plugins.webDeployment.config.AccessType
import com.jetbrains.plugins.webDeployment.config.Deployable
import com.jetbrains.plugins.webDeployment.config.GroupedServersConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.TerminalTabState
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.nio.file.Path
import java.time.Duration

/**
 * 远程终端的平台接线层：deployment 凭据、host key 预检、SSH 终端 tab、发送 / 读取。
 *
 * 这是**唯一** import `com.jetbrains.plugins.remotesdk.*` / `com.intellij.ssh.*` 的地方。
 * 只有 `remote-run` 插件存在时才会被加载（见 `remote-terminal.xml`）。
 */

private data class PreparedRemote(
    val credentials: RemoteCredentialsHolder,
    /** 远程登录 shell 的 basename：zsh / bash / fish / sh。 */
    val shell: String,
)

internal suspend fun runInRemoteTerminal(
    project: Project,
    serverName: String,
    command: String,
    input: String?,
    timeoutMs: Int,
    workingDirectory: String?,
    loginShell: Boolean,
    strictHostKey: Boolean,
    maxChars: Int = TERMINAL_DEFAULT_MAX_CHARS,
    tab: String? = null,
    newTab: Boolean = false,
): TerminalRunResult {
    val registry = project.service<TerminalSessionRegistry>()

    // 复用优先：点名了 tab 就用它；否则取这台服务器上的**默认** tab。
    // 和本地一致 —— 默认是"自动创建的那条"，不是"最近用过的"，落点才可预测。
    val autoCreate = tab == null && !newTab
    val reuseTarget = tab
        ?: if (!autoCreate) null else registry.defaultTab(TerminalKind.Remote, serverName)

    val tabName: String
    val widget: TerminalWidget
    val state: TerminalSessionRegistry.State
    val reuseName = reuseTarget
    val existing = reuseName?.let { registry.get(it) }
    // 自动挑中的 tab 可能已经被关掉：当作没有，重新连一个；
    // 点名的那条关掉了则明确报错，不能偷偷换一条（甚至换一台机器）。
    val reusable = if (existing != null && reuseName != null) {
        runCatching { requireRemoteWidget(project, reuseName, existing) }.getOrNull()
    } else {
        null
    }
    if (existing != null && reusable != null) {
        if (existing.serverName != null && existing.serverName != serverName) {
            throw TerminalAccessException(
                code = "tab_server_mismatch",
                message = "tab \"$reuseTarget\" 属于服务器 \"${existing.serverName}\"，不能用来跑 \"$serverName\" 的命令。",
            )
        }
        widget = reusable
        state = existing
        tabName = reuseTarget
    } else {
        if (tab != null) {
            throw TerminalAccessException(
                code = "terminal_tab_not_found",
                message = "远程终端 tab \"$tab\" 不存在或已被关闭。请传 newTab=true 新开一个，或用 list_terminals 查现有 tab。",
            )
        }
        val prepared = prepareRemote(project, serverName, strictHostKey)
        val aliveNames = aliveTerminalTabNames(project)
        tabName = registry.nextTabName(remoteTabPrefix(serverName), aliveNames::contains)
        widget = openRemoteTerminal(project, prepared, tabName)
        state = TerminalSessionRegistry.State(
            uiHandle = widget,
            lastText = normalizeTerminalSnapshot(textOf(widget)),
            kind = TerminalKind.Remote,
            workingDirectory = workingDirectory,
            serverName = serverName,
            shell = prepared.shell,
        )
        registry.put(tabName, state)
        if (autoCreate) registry.registerDefault(tabName, TerminalKind.Remote, serverName)
    }

    // 复用的 tab 可能还在跑上一条命令：一直退避等到它回到提示符（和本地同一套
    // 序列，不设上限），而不是把命令直接塞进正在跑的 shell。
    if (reusable != null && !awaitRemoteIdle(widget, state)) {
        throw TerminalAccessException(
            code = "terminal_tab_busy",
            message = "远程终端 \"$tabName\" 在等待期间被关闭，本次命令没有发送。请重新 execute_in_remote_host 建一个 tab。",
        )
    }

    // 复用的 tab 本身就是登录 shell：不再套 `zsh -lic`、也不用 `exec`，
    // 直接在它里面敲命令 —— 这样 cd / export / 后台进程才留得到下一条命令。
    val sentText = if (reusable != null) {
        buildRemoteCommandInTab(command, workingDirectory)
    } else {
        buildRemoteCommand(command, workingDirectory, state.shell ?: "sh", loginShell)
    }
    state.busy = true
    val before = normalizeTerminalSnapshot(textOf(widget))
    state.markers.add(TerminalCommandMarker(command, sentText, before))
    val commandStartedAt = System.currentTimeMillis()
    state.lastCommand = command
    state.lastCommandAt = commandStartedAt
    state.lastUsedAt = commandStartedAt

    withContext(Dispatchers.EDT) { widget.sendCommandToExecute(sentText) }
    if (!input.isNullOrEmpty()) {
        withContext(Dispatchers.EDT) { widget.ttyConnector?.write(input) }
    }

    val (currentRaw, timedOut) = waitForRemoteCommand(widget, sentText, before, timeoutMs)
    val current = normalizeTerminalSnapshot(currentRaw)

    val (delta, cut) = textDelta(before, current)
    val (text, cleanedCut) = stripCommandEchoAndPrompt(delta, sentText, maxChars)
    // 远程没有 command-finished 信号：等到提示符就算完成，超时则视为仍在运行。
    // timeoutMs 最低 60s，所以这里永远是"同步等到超时"这一条路径。
    val running = timedOut
    state.lastText = current
    state.lastExitCode = null
    state.lastTimedOut = timedOut
    // 丢给调用方的是 "null = 判断不了"；这里对复用保守一点：判断不了就当它还占着。
    state.lastRunning = running ?: true
    state.lastUsedAt = System.currentTimeMillis()
    state.busy = false
    return TerminalRunResult(
        tab = tabName,
        text = text,
        exitCode = null,
        timedOut = timedOut,
        running = running,
        truncated = cut || cleanedCut,
        warning = shellChangeWarning(command),
    )
}

internal suspend fun readRemoteTerminal(
    project: Project,
    tab: String,
    tail: Int?,
    full: Boolean,
    maxChars: Int = TERMINAL_DEFAULT_MAX_CHARS,
): TerminalReadResult {
    val state = project.service<TerminalSessionRegistry>().get(tab)
        ?: throw TerminalAccessException(
            code = "terminal_tab_not_found",
            message = "找不到终端 tab \"$tab\"。请使用 execute_in_remote_host 返回的 tab id。",
        )
    val widget = requireRemoteWidget(project, tab, state)
    state.lastUsedAt = System.currentTimeMillis()

    val currentRaw = textOf(widget)
    val current = normalizeTerminalSnapshot(currentRaw)
    // 提示符在末尾说明命令已结束；否则保持 null，不能仅凭输出断定还在运行。
    val running = if (current.lines().lastOrNull { it.isNotBlank() }?.let(::looksLikePrompt) == true) {
        false
    } else {
        null
    }

    if (full) {
        val (text, truncated) = cleanTerminalText(currentRaw, maxChars)
        return TerminalReadResult(tab, text, null, running, truncated)
    }

    if (tail != null) {
        val (segments, truncated) = buildRemoteCommandSegments(current, state.markers, tail, maxChars)
        return TerminalReadResult(tab, null, segments, running, truncated)
    }

    val (delta, cut) = textDelta(state.lastText, current)
    state.lastText = current
    val (text, truncated) = cleanTerminalText(delta, maxChars)
    return TerminalReadResult(tab, text, null, running, cut || truncated)
}

/**
 * 往远程终端发一次 Ctrl-C。
 *
 * 与本地一致：这是命令还跑着时唯一能生效的写入，且只发一个字节，不接文本。
 */
internal suspend fun interruptRemoteTerminal(project: Project, tab: String) {
    val state = project.service<TerminalSessionRegistry>().get(tab)
        ?: throw TerminalAccessException(
            code = "terminal_tab_not_found",
            message = "找不到终端 tab \"$tab\"。请使用 execute_in_remote_host 返回的 tab id。",
        )
    val widget = requireRemoteWidget(project, tab, state)
    state.lastUsedAt = System.currentTimeMillis()
    val connector = widget.ttyConnector
        ?: throw TerminalAccessException(
            code = "remote_terminal_not_ready",
            message = "远程终端 \"$tab\" 还没有建立 TTY 连接，无法写入。",
        )
    try {
        withContext(Dispatchers.EDT) { connector.write(TERMINAL_INTERRUPT_CHAR) }
    } catch (e: Throwable) {
        throw TerminalAccessException(
            code = "remote_terminal_closed",
            message = "远程终端 \"$tab\" 已退出（${e.message ?: "stream closed"}）。请重新 execute_in_remote_host 创建新 tab。",
            current = e.message,
        )
    }
}

// ---------------------------------------------------------------------------
// 预检 / 建 tab
// ---------------------------------------------------------------------------

/** 校验远程 tab 仍然存在；用户点 X 关掉 tab 后 widget 会 dispose。 */
private fun requireRemoteWidget(
    project: Project,
    tab: String,
    state: TerminalSessionRegistry.State,
): TerminalWidget {
    val widget = state.uiHandle as? TerminalWidget
        ?: throw TerminalAccessException(
            code = "terminal_tab_not_remote",
            message = "tab \"$tab\" 不是远程终端。本地终端请用 target=\"local\"。",
        )
    if (Disposer.isDisposed(widget)) {
        project.service<TerminalSessionRegistry>().remove(tab)
        throw TerminalAccessException(
            code = "terminal_closed",
            message = "远程终端 \"$tab\" 已被关闭，无法继续读取/写入。请重新 execute_in_remote_host 创建新 tab。",
        )
    }
    val connector = widget.ttyConnector
    if (connector != null && !connector.isConnected) {
        project.service<TerminalSessionRegistry>().remove(tab)
        throw TerminalAccessException(
            code = "terminal_closed",
            message = "远程终端 \"$tab\" 已退出，无法继续读取/写入。请重新 execute_in_remote_host 创建新 tab。",
        )
    }
    return widget
}

private fun prepareRemote(
    project: Project,
    serverName: String,
    strictHostKey: Boolean,
): PreparedRemote {
    val servers = GroupedServersConfigManager.getInstance(project).flattenedServers.orEmpty()
    val server = servers.firstOrNull { it.getName() == serverName }
        ?: throw TerminalAccessException(
            code = "deployment_server_not_found",
            message = """
                找不到 deployment server "$serverName"。

                修复步骤：
                1. 打开 Settings | Build, Execution, Deployment | Deployment
                2. 添加一个 SFTP 服务器，并为本项目加至少一个 mapping
                3. 点 Test Connection
            """.trimIndent(),
            current = "servers=${servers.map { it.getName() }}",
            fix = listOf(
                "Settings | Build, Execution, Deployment | Deployment",
                "Add an SFTP server",
                "Test Connection",
            ),
        )

    val accessType = runCatching { server.getAccessType() }.getOrNull()
    if (accessType != AccessType.SFTP) {
        throw TerminalAccessException(
            code = "unsupported_access_type",
            message = "deployment server \"$serverName\" 的类型是 ${accessType?.name ?: "UNKNOWN"}；只有 SFTP/SSH 支持远程执行。",
            current = "accessType=${accessType?.name}",
        )
    }

    val deployable = Deployable.create(server, project)
    val credentials = RemoteCredentialsHolder()
    deployable.copyToCredentials(credentials)

    // host / 私钥是“配置缺失”，必须比“凭据缺失”先判断：
    // hasCredentials() 在 host 或 keyPath 为空时也会返回 false，否则会被误报成没有凭据。
    val rawHost = credentials.host
    val host = rawHost.takeIf { it.isNotBlank() }
        ?: throw TerminalAccessException(
            code = "deployment_host_missing",
            message = """
                deployment server "$serverName" 没有配置 host。

                修复步骤：
                1. 打开 Settings | Build, Execution, Deployment | Deployment
                2. 选中该服务器，填写 Host
                3. 点 Test Connection
            """.trimIndent(),
            current = "host=${rawHost.ifBlank { "null" }}",
            fix = listOf(
                "Settings | Build, Execution, Deployment | Deployment",
                "Fill in Host",
                "Test Connection",
            ),
        )

    if (credentials.authType == AuthType.KEY_PAIR && credentials.privateKeyFile.isBlank()) {
        throw TerminalAccessException(
            code = "deployment_key_missing",
            message = """
                deployment server "$serverName" 使用 Key pair，但没有配置私钥文件。

                修复步骤：
                1. 打开 Settings | Build, Execution, Deployment | Deployment
                2. 选中该服务器，选择私钥文件
                3. 点 Test Connection
            """.trimIndent(),
            current = "authType=KEY_PAIR, keyPath=null",
            fix = listOf(
                "Settings | Build, Execution, Deployment | Deployment",
                "Select a private key file",
                "Test Connection",
            ),
        )
    }

    if (!deployable.hasCredentials()) {
        throw TerminalAccessException(
            code = "deployment_credentials_missing",
            message = """
                deployment server "$serverName" 没有可用的凭据。

                修复步骤：
                1. 打开 Settings | Build, Execution, Deployment | Deployment
                2. 选中该服务器，填写密码或私钥
                3. 点 Test Connection
            """.trimIndent(),
            fix = listOf(
                "Settings | Build, Execution, Deployment | Deployment",
                "Fill in credentials",
                "Test Connection",
            ),
        )
    }

    val knownHosts = listOf(File(System.getProperty("user.home"), ".ssh/known_hosts"))
    val verifier = OpenSshLikeHostKeyVerifier(
        knownHosts,
        if (strictHostKey) StrictHostKeyChecking.YES else StrictHostKeyChecking.ACCEPT_NEW,
        false,
        false,
    )

    val builder = ConnectionBuilder(host)
        .withParsingOpenSSHConfig(
            credentials.shouldUseOpenSshConfig() || credentials.authType == AuthType.OPEN_SSH,
        )
        .withSshPasswordProvider(PlatformSshPasswordProvider(credentials, false, null))
        .withSshConnectionConfig { base ->
            base.copy(
                authMethods = authMethods(credentials),
                hostKeyVerifier = verifier,
                port = credentials.port,
                user = credentials.userName.orEmpty(),
                // 保留 withParsingOpenSSHConfig / 默认配置解析出的 ProxyJump / proxy，不要覆盖成 null
                connectTimeout = Duration.ofSeconds(10),
            )
        }

    val shell = try {
        val process = builder.execBuilder("printf '%s' \"\$SHELL\"")
            .withAllocatePty(false)
            .execute(10, false)
        val out = process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()
        process.waitFor()
        out.trim().substringAfterLast('/').ifEmpty { "sh" }
    } catch (e: Throwable) {
        val detail = e.message.orEmpty()
        if (detail.contains("host key", true) ||
            detail.contains("verify", true) ||
            detail.contains("fingerprint", true)
        ) {
            throw TerminalAccessException(
                code = "host_key_rejected",
                message = """
                    主机密钥校验失败：${e.message}

                    当前是严格模式（strictHostKey=true）。请先在 IDE 的 SSH 终端或 Deployment Test Connection 里接受该主机的密钥，
                    或改用 strictHostKey=false（accept-new：首次接受并记录，之后变化拒绝）。
                """.trimIndent(),
                current = detail,
            )
        }
        throw TerminalAccessException(
            code = "ssh_connect_failed",
            message = "连接远程主机失败：${e.message}",
            current = detail,
        )
    }

    return PreparedRemote(credentials, shell)
}

private fun authMethods(credentials: RemoteCredentialsHolder): AuthMethods = when (credentials.authType) {
    AuthType.PASSWORD -> AuthMethods.empty.plus(AuthMethod.Password)
    AuthType.KEY_PAIR -> {
        val key = credentials.privateKeyFile
        if (key.isBlank()) {
            throw TerminalAccessException(
                code = "deployment_key_missing",
                message = "deployment server 使用 Key pair，但没有配置私钥文件。",
            )
        }
        AuthMethods.empty.plus(AuthMethod.PublicKey(listOf(Path.of(key))))
    }
    AuthType.OPEN_SSH -> AuthMethods.empty
    else -> AuthMethods.empty
}

private suspend fun openRemoteTerminal(
    project: Project,
    prepared: PreparedRemote,
    tabName: String,
): TerminalWidget {
    val runner = SshTerminalCachingRunner(project, prepared.credentials, Charsets.UTF_8)
    try {
        runner.connect()
    } catch (e: Throwable) {
        val detail = buildList {
            e.message?.takeIf { it.isNotBlank() }?.let(::add)
            e.cause?.message?.takeIf { it.isNotBlank() }?.let(::add)
            if (isEmpty()) add(e::class.java.simpleName)
        }.distinct().joinToString(" | ")
        throw TerminalAccessException(
            code = "remote_terminal_not_started",
            message = """
                打开远程终端失败：交互式 shell channel 没有建立。

                SSH 预检已经成功，但创建终端时又建立了一次连接；常见原因是两次连接之间网络中断、
                服务器限制并发 SSH 会话（MaxSessions / MaxStartups）、远程 shell 被 sshd 拒绝，
                或 IDE 的 remote-run / terminal 组件启动失败。

                排查步骤：
                1. 重试 execute_in_remote_host
                2. 在 IDE 的 SSH Terminal 里手动连接同一服务器，确认能打开交互式 shell
                3. 检查服务器 sshd 的 MaxSessions / MaxStartups、防火墙和 VPN
            """.trimIndent(),
            current = detail,
            fix = listOf(
                "Retry execute_in_remote_host",
                "Open an SSH Terminal for the same server",
                "Check sshd MaxSessions / MaxStartups, firewall and VPN",
            ),
        )
    }

    val tabState = TerminalTabState().apply {
        myTabName = tabName
        myIsUserDefinedTabTitle = true
    }

    val widget = withContext(Dispatchers.EDT) {
        TerminalToolWindowManager.getInstance(project).createNewSession(runner, tabState, null)
    }
    // 等登录 banner 之后的第一条提示符
    waitForRemotePrompt(widget, 8_000)
    return widget
}

// ---------------------------------------------------------------------------
// 工具函数
// ---------------------------------------------------------------------------

/**
 * 退避等远程 tab 回到提示符，**不设上限**。
 *
 * 远程没有 command-finished 信号，只能用「最后一行像提示符」判断；本插件自己的
 * 命令还在发送中（[TerminalSessionRegistry.State.busy]）时一定算忙。
 * 序列与本地一致：250ms、500ms，之后每次 1000ms；只有读不到终端时返回 false。
 *
 * 与 [awaitLocalIdle] 相同：无限等待依赖客户端挂着 `GET /stream` 的 SSE 流；
 * 不挂流的客户端会在 15 秒后被 IDE 清掉会话（pending transport 超时）。
 */
private suspend fun awaitRemoteIdle(
    widget: TerminalWidget,
    state: TerminalSessionRegistry.State,
): Boolean {
    var attempt = 0
    while (true) {
        if (!state.busy) {
            val text = runCatching { textOf(widget) }.getOrElse { return false }
            val lastLine = text.lines().lastOrNull { it.isNotBlank() }
            if (lastLine != null && looksLikePrompt(lastLine)) return true
        }
        delay(retryBackoffMs(attempt++))
    }
}

private suspend fun textOf(widget: TerminalWidget): String =
    withContext(Dispatchers.EDT) { widget.getText().toString() }

/** 等第一行像提示符的输出出现（登录 banner 之后）。 */
private suspend fun waitForRemotePrompt(widget: TerminalWidget, timeoutMs: Int): String {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = ""
    while (System.currentTimeMillis() < deadline) {
        last = textOf(widget)
        val lastLine = last.lines().lastOrNull { it.isNotBlank() }
        if (lastLine != null && looksLikePrompt(lastLine)) return last
        delay(200)
    }
    return last
}

/**
 * 等命令执行完：找到命令回显那一行，再等它之后出现一条新的提示符。
 *
 * 比「输出安静 800ms」可靠：zsh -lic 启动、编译等阶段可能长时间没有输出，
 * 安静检测会提前返回命令回显，拿不到真正的结果。
 */
private suspend fun waitForRemoteCommand(
    widget: TerminalWidget,
    sentText: String,
    before: String,
    timeoutMs: Int,
): Pair<String, Boolean> {
    val deadline = System.currentTimeMillis() + timeoutMs
    val needle = sentText.take(60)
    var last = textOf(widget)
    while (System.currentTimeMillis() < deadline) {
        last = textOf(widget)
        val lines = last.lines()
        val echo = lines.indexOfLast { it.contains(needle) }
        if (echo >= 0) {
            val after = lines.drop(echo + 1).dropLastWhile { it.isBlank() }
            if (after.any { looksLikePrompt(it) }) return last to false
        }
        // clear / reset 会抹掉命令回显：文本已经变化且末尾是提示符，也视为执行完。
        // 必须用归一化文本比较：last 带终端补位空格，before 是归一化后的快照。
        val lastLine = lines.lastOrNull { it.isNotBlank() }
        if (lastLine != null &&
            looksLikePrompt(lastLine) &&
            normalizeTerminalSnapshot(last) != before
        ) {
            return last to false
        }
        delay(200)
    }
    return textOf(widget) to true
}
