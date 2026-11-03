package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.project.Project
import com.intellij.ssh.ExecChannelConfig
import com.intellij.ssh.SessionConfig
import com.intellij.ssh.SshConnectionService
import com.intellij.ssh.channels.ExecChannel
import com.intellij.ssh.config.AuthMethod
import com.intellij.ssh.config.AuthMethods
import com.intellij.ssh.config.SshConnectionConfig
import com.intellij.ssh.config.unified.SshConfig
import com.intellij.ssh.config.unified.SshConfigManager
import com.intellij.ssh.interaction.ConstantPasswordProvider
import com.intellij.ssh.process.SshExecProcess
import java.nio.file.Path

/**
 * 用 **IDE 自带的 SSH**（`SshConnectionService`）执行远端命令。
 *
 * 不引入第二套 SSH 实现：凭据、host key 校验、agent、连接复用都走平台那一套，
 * 与用户在 IDE 里点 "Test Connection" 用的是同一条链路。
 *
 * 密码 / 私钥口令从 `SshConfig` 的 PasswordSafe 读取，交给
 * [ConstantPasswordProvider] —— 不落盘，也不出现在命令行里。
 */
internal object RemoteSshClient {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(RemoteSshClient::class.java)

    /** 从 IDE 的 SSH 配置里按名字找一条。 */
    fun sshConfig(project: Project, name: String): SshConfig? =
        runCatching { SshConfigManager.getInstance(project).findConfigByName(name) }.getOrNull()

    /**
     * 同步执行一条远端命令，返回 stdout。
     *
     * @throws IllegalStateException 连接失败 / 退出码非 0。
     */
    fun run(project: Project, serverName: String, remoteCommand: String): String {
        val config = sshConfig(project, serverName)
            ?: throw IllegalStateException("IDE 的 SSH 配置里没有 \"$serverName\"。")
        LOG.info("RemoteSshClient.run start: $serverName: ${remoteCommand.take(120)}")
        // 整个「连接 + 执行 + 读流」都放到固定线程：EDT 或 coroutine 的 dispatch 线程上
        // 直接调 sshj 会被判成 "Blocking SSH method called from the dispatch or write thread"。
        return execExecutor.submit<String> {
            val process = SshExecProcess(createChannel(config, remoteCommand), false)
            try {
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                LOG.info("RemoteSshClient.run done: $serverName: exit=$exitCode")
                if (exitCode != 0) {
                    throw IllegalStateException(
                        "ssh 执行失败（exit $exitCode）：${stderr.trim().ifEmpty { stdout.trim() }}",
                    )
                }
                stdout
            } finally {
                runCatching { process.destroy() }
            }
        }.get()
    }

    /**
     * 起一个 `tail -f` 的远端进程，给 Run 窗口显示日志。
     *
     * `SshExecProcess` 实现了 `Process`，可以直接交给 `OSProcessHandler`。
     */
    /** 返回原始 `ExecChannel`：调用方自己读流（`OSProcessHandler` 的 reader 线程读不到 sshj 的通道）。 */
    fun tail(project: Project, serverName: String, remoteCommand: String): ExecChannel {
        val config = sshConfig(project, serverName)
            ?: throw IllegalStateException("IDE 的 SSH 配置里没有 \"$serverName\"。")
        return execExecutor.submit<ExecChannel> {
            createChannel(config, remoteCommand)
        }.get()
    }

    /** 跑一条命令，输出非空就算活着；查询失败按活着处理，避免误删记录。 */
    fun alive(project: Project, serverName: String, remoteCommand: String): Boolean = runCatching {
        run(project, serverName, remoteCommand).trim().isNotEmpty()
    }.getOrDefault(true)

    /** 批量查远端哪些 pid 还活着。 */
    fun alivePids(project: Project, serverName: String, pids: Collection<Long>): Set<Long> {
        if (pids.isEmpty()) return emptySet()
        val list = pids.joinToString(",")
        val output = run(project, serverName, "ps -p $list -o pid= 2>/dev/null || true")
        return output.lineSequence()
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
    }

    /** 远端 PID 是否还活着。 */
    fun isAlive(project: Project, serverName: String, pid: Long): Boolean = runCatching {
        val output = run(project, serverName, "ps -p $pid -o pid= 2>/dev/null || true")
        output.trim().toLongOrNull() == pid
    }.getOrDefault(false)

    /** 先 TERM，1 秒后还在就 KILL。 */
    fun kill(project: Project, serverName: String, pid: Long) {
        run(
            project,
            serverName,
            "kill -TERM $pid 2>/dev/null; sleep 1; " +
                "kill -0 $pid 2>/dev/null && kill -9 $pid 2>/dev/null; true",
        )
    }

    /**
     * SSH 调用放在专用线程。
     *
     * MCP 的 `execute_run_configuration` 跑在 coroutine 的 `DefaultDispatcher` 上，
     * 线程名里带 "dispatch"，sshj 会把它误判成自己的 dispatch 线程并抛
     * "Blocking SSH method called from the dispatch or write thread"。
     * 平台的安全入口 `Ssh.wrapBlockingSshCall` 是 Internal API（编译期不可见），
     * 所以用一个固定名字的线程自己兜住。
     */
    internal val execExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-ssh-exec").apply { isDaemon = true }
    }

    /** 连接 + 执行都要在这个线程里做；sshj 会检查调用线程。 */
    private fun createChannel(config: SshConfig, remoteCommand: String): ExecChannel =
        SshConnectionService.instance.exec(sessionConfigOf(config), ExecChannelConfig(remoteCommand))

    /** 把 `SshConfig` 拍成平台需要的 `SessionConfig`。 */
    internal fun sessionConfigOf(config: SshConfig): SessionConfig {
        val authData = runCatching { config.authDataFromForPasswordSafe }.getOrNull()
        val authMethods = when (config.authType.name) {
            "PASSWORD" -> AuthMethods.empty.plus(AuthMethod.Password)
            "KEY_PAIR" -> {
                val key = config.keyPath.takeIf { it.isNotBlank() }?.let(::expandUserHomeMacro)
                    ?: throw IllegalStateException("SSH 配置 \"${config.name}\" 没有私钥路径。")
                AuthMethods.empty.plus(
                    AuthMethod.PublicKey(listOf(Path.of(key)), AuthMethod.PublicKey.Agent.NO),
                )
            }
            else -> AuthMethods.empty.plus(
                AuthMethod.PublicKey(emptyList(), AuthMethod.PublicKey.Agent.ALL),
            )
        }

        val connectionConfig = SshConnectionConfig(config.host).copy(
            port = config.port,
            user = config.username,
            authMethods = authMethods,
        )
        return SessionConfig(
            initialHost = config.host,
            connectionConfig = connectionConfig,
            sshPasswordProvider = ConstantPasswordProvider(
                { authData?.passphrase?.takeIf { it.isNotEmpty() } },
                authData?.password?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    private fun expandUserHomeMacro(path: String): String {
        val home = System.getProperty("user.home").orEmpty()
        return path.replace("\$USER_HOME\$", home).replace("\${USER_HOME}", home)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
