package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ssh.channels.ExecChannel
import java.io.File
import java.io.OutputStream
import java.time.Instant

/**
 * 一次 `Remote Process` 的执行。
 *
 * `execute` 立即返回 Console + ProcessHandler，真正的同步 / 启动放在 handler 的
 * `startNotify` 后台线程里 —— 这样 Run 窗口会先出现，进度和日志都写在里面：
 *
 * ```
 * [同步] 共 42 个文件 → /data/app
 * [同步] 1/42  pom.xml
 * ...
 * [启动] 进程已启动 (pid 12345)
 * （进程日志）
 * ```
 *
 * 两种类型：
 * - `process`：`nohup` 启动，日志 tail 文件
 * - `docker`：`docker rm -f` 后用 command 重建，日志用 `docker logs`
 */
class RemoteProcessRunProfileState(
    private val configuration: RemoteProcessRunConfiguration,
    private val environment: ExecutionEnvironment,
) : RunProfileState {

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = configuration.project
        val options = configuration.remoteOptions
        val server = options.server?.takeIf { it.isNotBlank() }
            ?: throw ExecutionException("没有选择 SSH 配置。")
        val command = options.command?.takeIf { it.isNotBlank() }
            ?: throw ExecutionException("command 不能为空。")
        val kind = options.kind?.takeIf { it.isNotBlank() } ?: inferKind(command)
        // Deploy 是一次性标记：读到就清，避免下次 Run 误用。
        val deployOnly = options.deployOnly
        options.deployOnly = false
        // MCP Deploy 的自动替换：不弹模态框，直接停旧重启。
        val autoReplace = options.autoReplace
        options.autoReplace = false

        val handler = RemoteProcessHandler(
            project = project,
            server = server,
            killOnStop = !deployOnly,
            startAction = { notify ->
                startRemoteProcess(
                    project = project,
                    options = options,
                    configurationName = configuration.name,
                    server = server,
                    command = command,
                    kind = kind,
                    notify = notify,
                    confirmRestart = { message ->
                        if (autoReplace) true else confirmRestart(project, message)
                    },
                )
            },
        )
        val console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console
        console.attachToProcess(handler)
        return DefaultExecutionResult(console, handler)
    }

}

/** 同步 + 启动。`notify` 把进度写进 Run 窗口 / 部署结果。 */
internal fun startRemoteProcess(
    project: Project,
    options: RemoteProcessOptions,
    configurationName: String,
    server: String,
    command: String,
    kind: String,
    notify: (String) -> Unit,
    confirmRestart: (String) -> Boolean,
): RemoteStartOutcome {
    val docker = RemoteProcessHosts.serverDockerCommand(project, server)

    val stopAction: () -> Unit
    val logCommand: String
    var replaced = false
    var pid: Long? = null
    var container: String? = null
    var workingDirectory: String? = null

    when (kind) {
        "docker" -> {
            notify("[启动] 重建容器...")
            // 容器名必须来自配置：command 里有没有 --name 都要对齐到这个名字，
            // 否则每次生成的容器名不同，状态查询（docker ps -f name=）就对不上。
            val name = options.container?.takeIf { it.isNotBlank() }
                ?: throw ExecutionException("docker 需要 Container 名（用于判断容器是否已启动）。")
            container = name
            val containerRunning = runCatching {
                RemoteSshClient.run(project, server, "$docker ps -q -f name=^$name$").trim().isNotEmpty()
            }.getOrDefault(false)
            if (containerRunning) {
                if (!confirmRestart("容器 $name 已在运行，是否停止并重新启动？")) {
                    throw ExecutionException("已取消：容器 $name 已在运行。")
                }
                replaced = true
            }
            val dockerCommand = ensureDockerName(command, name)
            val dockerLine = "$docker rm -f ${quote(name)} >/dev/null 2>&1; $dockerCommand"
            notify("[启动] $ $dockerLine")
            withHeartbeat(notify) {
                RemoteSshClient.run(project, server, dockerLine)
            }
            stopAction = {
                runCatching {
                    RemoteSshClient.run(
                        project,
                        server,
                        "$docker stop ${quote(name)} >/dev/null 2>&1; " +
                            "$docker rm -f ${quote(name)} >/dev/null 2>&1; true",
                    )
                }
            }
            logCommand = "$docker logs -f --tail 200 ${quote(name)}"
            notify("[启动] 容器 $name 已启动")
        }

        else -> {
            val deploymentName = options.deployment?.takeIf { it.isNotBlank() }
                ?: throw ExecutionException("Deployment 不能为空。")
            val deployment = RemoteProcessHosts.deployments(project, server)
                .firstOrNull { it.name == deploymentName }
                ?: throw ExecutionException("服务器 \"$server\" 下没有名为 \"$deploymentName\" 的 deployment。")
            val directory = options.workingDirectory?.takeIf { it.isNotBlank() }
                ?: deployment.deployPath
            workingDirectory = directory
            // 我们只捕获 stdout/stderr；应用自己的日志目录是另一个字段，只用于查询。
            val log = "$directory/.opencode/output-${configurationName.replace('/', '-')}.log"

            // 先校验：已在跑就问是否重启；用户取消时不做任何同步。
            val existing = RemoteProcessFinder.find(project, server, command, directory)
            if (existing != null) {
                if (!confirmRestart("远端已有同名进程 (pid $existing)，是否停止并重新启动？")) {
                    throw ExecutionException("已取消：远端已有同名进程 (pid $existing)。")
                }
                replaced = true
                notify("[启动] 停止旧进程 (pid $existing)...")
                RemoteSshClient.kill(project, server, existing)
            }

            notify("[启动] 同步 deployment...")
            syncDeployments(
                project,
                server,
                listOf(
                    DeploymentSyncRequest(
                        name = deployment.name,
                        localPath = deployment.localPath,
                        deployPath = deployment.deployPath,
                        exclude = deployment.exclude,
                    ),
                ),
            )
            val excludes = RemoteProcessHosts.serverExclude(project, server) + deployment.exclude
            RemoteProcessSync.upload(
                project,
                server,
                deployment.localPath,
                deployment.deployPath,
                excludes,
                notify,
            )

            notify("[启动] 拉起进程...")
            val line = remoteStartLine(directory, command, log)
            notify("[启动] $ $line")
            pid = withHeartbeat(notify) {
                startRemote(project, server, line)
            }
            stopAction = {
                runCatching {
                    val found = RemoteProcessFinder.find(project, server, command, directory)
                    if (found != null) RemoteSshClient.kill(project, server, found)
                }
            }
            logCommand = "tail -f ${quote(log)}"
            notify("[启动] 进程已启动 (pid $pid)")
        }
    }

    return RemoteStartOutcome(
        stopAction = stopAction,
        logCommand = logCommand,
        replaced = replaced,
        pid = pid,
        container = container,
        workingDirectory = workingDirectory,
    )
}

/** 已在运行时问一句是否重启；用户取消就中止，不做任何同步 / 启动。 */
private fun confirmRestart(project: Project, message: String): Boolean {
    var restart = false
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
        restart = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            message,
            "Remote Process",
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        ) == com.intellij.openapi.ui.Messages.YES
    }
    return restart
}

/**
 * 应用自己的日志目录（log4j / logback 配的那个），只用于查询，不参与启动 / tail。
 *
 * - process：相对路径按 working directory 解析
 * - docker：容器内路径，原样返回
 */
internal fun resolveAppLogDir(configured: String?, workingDirectory: String?): String? {
    val raw = configured?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (java.nio.file.Path.of(raw).isAbsolute) return raw
    return if (workingDirectory.isNullOrBlank()) raw else "$workingDirectory/$raw"
}

/** `nohup` 启动的完整远端命令。 */
private fun remoteStartLine(workingDirectory: String, command: String, logFile: String): String {
    val logDir = logFile.substringBeforeLast('/', ".")
    return "mkdir -p '${escape(logDir)}'; touch '${escape(logFile)}'; " +
        "cd '${escape(workingDirectory)}' && exec nohup $command >> '${escape(logFile)}' 2>&1 & echo \$!"
}

private fun startRemote(project: Project, server: String, line: String): Long {
    val output = RemoteSshClient.run(project, server, line)
    return output.lineSequence().map { it.trim() }.lastOrNull { it.toLongOrNull() != null }?.toLong()
        ?: throw ExecutionException("启动失败，没有拿到远端 PID：${output.trim()}")
}

/**
 * 启动阶段的心跳：每 5 秒写一行 `[启动中] Ns`。
 *
 * 启动是一次阻塞的 SSH 调用，没有进度回调；极端情况下可能等几分钟，
 * 没有心跳用户会以为卡死了。SSH 本身不设超时，由用户点 Stop 取消。
 */
private fun <T> withHeartbeat(notify: (String) -> Unit, block: () -> T): T {
    val startedAt = System.currentTimeMillis()
    val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    val thread = Thread({
        while (!stopped.get()) {
            try {
                Thread.sleep(5000)
            } catch (_: InterruptedException) {
                break
            }
            if (stopped.get()) break
            notify("[启动中] ${(System.currentTimeMillis() - startedAt) / 1000}s")
        }
    }, "opencode-start-heartbeat").apply { isDaemon = true }
    thread.start()
    try {
        return block()
    } finally {
        stopped.set(true)
        thread.interrupt()
    }
}

private fun escape(value: String): String = value.replace("'", "'\\''")

/**
 * 远端启动完成的标记。
 *
 * `RemoteProcessHandler` 不是 OS 进程（没有本地 pid），而平台的 run 生命周期工具靠
 * "pid 可读 / 进程已退出"判断是否启动完成。没有这些标记，`rerun_run_configuration` 会
 * 一直等到 300 秒超时才返回。
 */
internal val REMOTE_PROCESS_STARTED: Key<Boolean> = Key.create("opencode.remote.process.started")

/** 远端 PID；docker 容器没有对应值，只靠 [REMOTE_PROCESS_STARTED]。 */
internal val REMOTE_PROCESS_PID: Key<Long> = Key.create("opencode.remote.process.pid")

/** 一次启动的结果：Stop 时做什么、日志命令，以及可返回给调用方的事实。 */
internal data class RemoteStartOutcome(
    val stopAction: () -> Unit,
    val logCommand: String,
    val replaced: Boolean,
    val pid: Long?,
    val container: String?,
    val workingDirectory: String?,
)

/**
 * 在后台线程里完成「同步 → 启动 → tail 日志」，并把进度写进 Run 窗口。
 *
 * `execute` 里同步做这些的话，Run 窗口要等全部完成才出现，用户看不到任何过程。
 */
private class RemoteProcessHandler(
    private val project: Project,
    private val server: String,
    private val killOnStop: Boolean,
    private val startAction: ((String) -> Unit) -> RemoteStartOutcome,
) : ProcessHandler() {

    @Volatile
    private var cancelled = false

    @Volatile
    private var channel: ExecChannel? = null

    @Volatile
    private var stopAction: (() -> Unit)? = null

    override fun startNotify() {
        super.startNotify()
        Thread({
            try {
                val outcome = startAction { text ->
                    notifyTextAvailable("$text\n", ProcessOutputType.STDOUT)
                }
                if (cancelled) return@Thread

                outcome.pid?.let { putUserData(REMOTE_PROCESS_PID, it) }
                putUserData(REMOTE_PROCESS_STARTED, true)
                stopAction = outcome.stopAction
                notifyTextAvailable("[日志] 连接远端日志...\n", ProcessOutputType.STDOUT)
                channel = RemoteSshClient.tail(project, server, outcome.logCommand)
                readChannel(channel!!)
            } catch (t: Throwable) {
                if (!cancelled) {
                    notifyTextAvailable("[失败] ${t.message ?: t::class.java.simpleName}\n", ProcessOutputType.STDERR)
                }
                notifyProcessTerminated(1)
            }
        }, "opencode-remote-process").apply { isDaemon = true }.start()
    }

    private fun readChannel(channel: ExecChannel) {
        try {
            channel.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    notifyTextAvailable(String(buffer, 0, count), ProcessOutputType.STDOUT)
                }
            }
        } catch (_: Throwable) {
            // 通道被 close 时 read 会抛，正常退出路径
        } finally {
            notifyProcessTerminated(0)
        }
    }

    override fun destroyProcessImpl() {
        cancelled = true
        // Deploy 出来的进程是发布语义：Stop 只关日志，进程继续在远端跑。
        if (killOnStop) stopAction?.invoke()
        runCatching { channel?.close() }
        notifyProcessTerminated(0)
    }

    override fun detachProcessImpl() {
        cancelled = true
        runCatching { channel?.close() }
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}

/** 从启动命令推断 kind：`docker run` → docker，其余 process。 */
internal fun inferKind(command: String): String {
    val text = command.trim()
    if (Regex("^(sudo\\s+)?docker\\s+run\\b").containsMatchIn(text)) return "docker"
    return "process"
}

/** 保证 `docker run` 里有 `--name <container>`：有就替换，没有就插入。 */
internal fun ensureDockerName(command: String, container: String): String {
    val trimmed = command.trim()
    val nameRegex = Regex("(?:^|\\s)--name[=\\s]+\\S+")
    return if (nameRegex.containsMatchIn(trimmed)) {
        trimmed.replace(nameRegex, " --name $container")
    } else {
        trimmed.replaceFirst("docker run", "docker run --name $container")
    }
}

/** 从 `docker run --name x` 里取容器名。 */
internal fun containerNameOf(command: String): String? =
    Regex("(?:^|\\s)--name[=\\s]+([^\\s]+)").find(command)?.groupValues?.get(1)

internal fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
