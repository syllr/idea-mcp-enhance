package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Remote Process 专用的 MCP 工具。
 *
 * 启停 / 状态本来就走内置的 run configuration 工具；这里补两个内置工具没有的能力：
 * **部署**（和配置页 Deploy 按钮一样开 Run 标签页，但 Stop 只关日志）和
 * **无状态的远端进程列表**。
 */
class RemoteProcessToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(DeployRemoteProcessTool(), ListRemoteProcessesTool())
}

private class DeployRemoteProcessTool : BaseMcpTool() {

    override val descriptor = deployRemoteProcessDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject()
            ?: return invalidArgument("$DEPLOY_REMOTE_PROCESS_TOOL_NAME: no project is bound to this MCP session.")
        val name = arguments.optionalString("configurationName")
            ?: return invalidArgument("Missing required argument `configurationName`.")

        val settings = RunManager.getInstance(project).findConfigurationByName(name)
            ?: return McpToolCallResult.error(
                "找不到名为 \"$name\" 的运行配置。",
                buildJsonObject { put("configurationName", name) },
            )
        val configuration = settings.configuration as? RemoteProcessRunConfiguration
            ?: return McpToolCallResult.error(
                "\"$name\" 不是 Remote Process 配置（type=${settings.type.id}）。",
                buildJsonObject {
                    put("configurationName", name)
                    put("type", settings.type.id)
                },
            )
        configuration.checkConfiguration()

        val options = configuration.remoteOptions
        val server = options.server?.takeIf { it.isNotBlank() }
            ?: return McpToolCallResult.error("没有选择 SSH 配置。", null)
        val command = options.command?.takeIf { it.isNotBlank() }
            ?: return McpToolCallResult.error("command 不能为空。", null)
        val kind = options.kind?.takeIf { it.isNotBlank() } ?: inferKind(command)

        val environment = buildRunEnvironment(project, settings)
            ?: return McpToolCallResult.error(
                "无法为 \"$name\" 构建执行环境；检查配置是否能被 runner 启动。",
                buildJsonObject { put("configurationName", name) },
            )

        // 和配置页里的 Deploy 按钮完全一致：标记 deployOnly，走 Run 框架打开一个 Run 标签页。
        // RemoteProcessRunProfileState.execute 读到这个标记后，Stop 只关闭日志，不 kill 远端。
        configuration.remoteOptions.deployOnly = true
        configuration.remoteOptions.autoReplace = true
        // Before launch 跑的是**独立会话**，日志落在它自己那个键下；记下 deploy 起始时刻，
        // 之后用它把「这次运行的那一条构建日志」从被挂配置的历史里挑出来。
        val deployStartedAtMillis = System.currentTimeMillis()
        val outcome = try {
            startRunAndAwait(
                project = project,
                environment = environment,
                settings = settings,
                startTimeoutMs = defaultStartTimeoutMs(),
            )
        } catch (t: Throwable) {
            configuration.remoteOptions.deployOnly = false
            configuration.remoteOptions.autoReplace = false
            throw t
        }
        // submittedOnly = before-launch 还没跑完，标记留给 execute 自己清。
        if (!outcome.submittedOnly) {
            configuration.remoteOptions.deployOnly = false
            configuration.remoteOptions.autoReplace = false
        }

        val steps = deploySteps(project, settings, deployStartedAtMillis, outcome.fullOutputPath)

        if (outcome.failure != null) {
            return McpToolCallResult.error(
                "部署失败：${outcome.failure}",
                buildJsonObject {
                    put("configurationName", configuration.name)
                    outcome.fullOutputPath?.let { put("fullOutputPath", it) }
                    putJsonArray("steps") { steps.forEach { add(it.toJson()) } }
                },
            )
        }

        val summary = buildString {
            append('[').append(configuration.name).append("] 已部署")
            append(if (outcome.started) "，Run 标签页已打开" else "，启动结果未知")
            outcome.pid?.let { append("，pid ").append(it) }
            outcome.fullOutputPath?.let { append("\n日志: ").append(it) }
            // 把 Before launch 那一步的日志直接点名：它在另一个文件里，
            // 不写出来 agent 只能去临时目录按时间猜。
            steps.filter { it.kind == "beforeLaunch" }.forEach { step ->
                val code = step.exitCode?.let { " (exit $it)" }.orEmpty()
                append("\n构建: ").append(step.name).append(code).append(" — ").append(step.logFile ?: "无日志")
            }
        }
        return McpToolCallResult.text(
            summary,
            buildJsonObject {
                put("configurationName", configuration.name)
                put("server", server)
                put("kind", kind)
                put("started", outcome.started)
                put("submittedOnly", outcome.submittedOnly)
                outcome.sessionId?.let { put("sessionId", it) }
                outcome.pid?.let { put("pid", it) }
                options.container?.takeIf { it.isNotBlank() }?.let { put("container", it) }
                options.workingDirectory?.takeIf { it.isNotBlank() }?.let { put("workingDirectory", it) }
                outcome.fullOutputPath?.let { put("fullOutputPath", it) }
                putJsonArray("steps") { steps.forEach { add(it.toJson()) } }
                put("output", outcome.output)
            },
        )
    }

    /**
     * 一次 deploy 由哪几步组成：Before launch 的每一条构建，加上 deploy 自己。
     *
     * Before launch 是**独立的 Run 会话**（自己的 `ProcessHandler`、自己的日志文件），
     * deploy 的聚合日志里永远不会有它的输出。所以这里把它按「deploy 起始时刻之后的最新一条
     * 被挂配置日志」关联进来，调用方拿到路径就能直接读。
     */
    private fun deploySteps(
        project: Project,
        settings: RunnerAndConfigurationSettings,
        deployStartedAtMillis: Long,
        deployLogFile: String?,
    ): List<DeployStep> {
        val steps = mutableListOf<DeployStep>()
        val beforeRunTasks = readRunConfigurationDefinition(project, settings)?.beforeRunTasks.orEmpty()
        for (task in beforeRunTasks) {
            val name = task.configurationName ?: continue
            // 只认这次 deploy 开始之后的那一条：更早的属于上一次发布。
            val run = listRunLogs(project, name)
                .firstOrNull { it.startedAtMillis >= deployStartedAtMillis }
            steps += DeployStep(
                kind = "beforeLaunch",
                name = name,
                logFile = run?.logFile,
                exitCode = run?.exitCode,
                enabled = task.enabled,
                output = run?.logFile?.let(::readRunLogTail),
            )
        }
        if (deployLogFile != null) {
            steps += DeployStep(
                kind = "deploy",
                name = settings.name,
                logFile = deployLogFile,
                exitCode = null,
                enabled = true,
            )
        }
        return steps
    }
}

/** 无状态地列出项目里所有 Remote Process 配置，并实时查询远端存活状态。 */
private class ListRemoteProcessesTool : BaseMcpTool() {

    override val descriptor = listRemoteProcessesDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject()
            ?: return invalidArgument("$LIST_REMOTE_PROCESSES_TOOL_NAME: no project is bound to this MCP session.")
        val nameFilter = arguments.optionalString("configurationName")
        val serverFilter = arguments.optionalString("server")

        val items = RunManager.getInstance(project).allSettings
            .filter { it.type.id == "RemoteProcess" && !it.isTemplate }
            .mapNotNull { it.configuration as? RemoteProcessRunConfiguration }
            .filter { nameFilter == null || it.name == nameFilter }
            .mapNotNull { inspect(project, it, serverFilter) }

        val text = if (items.isEmpty()) {
            "没有匹配的 Remote Process 配置。"
        } else {
            items.joinToString("\n") { item ->
                val where = item.server.ifEmpty { "?" }
                val detail = when {
                    item.pid != null -> "  pid ${item.pid}"
                    item.container != null -> "  container ${item.container}"
                    else -> ""
                }
                val log = item.logDir?.let { "  logDir $it" }.orEmpty()
                "- [$where] ${item.configurationName}  ${item.kind}  ${item.status}$detail$log"
            }
        }
        return McpToolCallResult.text(
            text,
            buildJsonObject {
                putJsonArray("processes") { items.forEach { add(it.toJson()) } }
            },
        )
    }

    private fun inspect(
        project: Project,
        configuration: RemoteProcessRunConfiguration,
        serverFilter: String?,
    ): RemoteProcessItem? {
        val options = configuration.remoteOptions
        val server = options.server?.takeIf { it.isNotBlank() }
        if (serverFilter != null && server != serverFilter) return null
        val command = options.command?.takeIf { it.isNotBlank() }
        val kind = options.kind?.takeIf { it.isNotBlank() } ?: inferKind(command.orEmpty())

        fun invalid(error: String) = RemoteProcessItem(
            configurationName = configuration.name,
            server = server.orEmpty(),
            deployment = options.deployment?.takeIf { it.isNotBlank() },
            kind = kind,
            status = "invalid",
            pid = null,
            container = options.container?.takeIf { it.isNotBlank() },
            workingDirectory = options.workingDirectory?.takeIf { it.isNotBlank() },
            logDir = null,
            error = error,
        )

        if (server == null) return invalid("没有选择 SSH 配置。")
        if (command == null) return invalid("command 不能为空。")

        return when (kind) {
            "docker" -> {
                val container = options.container?.takeIf { it.isNotBlank() }
                    ?: return invalid("docker 需要 Container 名。")
                val running = runCatching {
                    val docker = RemoteProcessHosts.serverDockerCommand(project, server)
                    RemoteSshClient.run(project, server, "$docker ps -q -f name=^$container$").trim().isNotEmpty()
                }.getOrElse { return invalid(it.message ?: "查询容器状态失败。") }
                RemoteProcessItem(
                    configurationName = configuration.name,
                    server = server,
                    deployment = options.deployment?.takeIf { it.isNotBlank() },
                    kind = kind,
                    status = if (running) "running" else "stopped",
                    pid = null,
                    container = container,
                    workingDirectory = null,
                    logDir = resolveAppLogDir(options.logDir, null),
                    error = null,
                )
            }

            "process" -> {
                val deploymentName = options.deployment?.takeIf { it.isNotBlank() }
                    ?: return invalid("Deployment 不能为空。")
                val deployments = runCatching { RemoteProcessHosts.deployments(project, server) }
                    .getOrElse { return invalid(it.message ?: "读取 deployment 失败。") }
                val deployment = deployments.firstOrNull { it.name == deploymentName }
                    ?: return invalid("找不到 deployment \"$deploymentName\"。")
                val workingDirectory = options.workingDirectory?.takeIf { it.isNotBlank() }
                    ?: deployment.deployPath
                val pid = runCatching {
                    RemoteProcessFinder.find(project, server, command, workingDirectory)
                }.getOrElse { return invalid(it.message ?: "查询进程状态失败。") }
                RemoteProcessItem(
                    configurationName = configuration.name,
                    server = server,
                    deployment = deployment.name,
                    kind = kind,
                    status = if (pid != null) "running" else "stopped",
                    pid = pid,
                    container = null,
                    workingDirectory = workingDirectory,
                    logDir = resolveAppLogDir(options.logDir, workingDirectory),
                    error = null,
                )
            }

            else -> invalid("kind 只能是 process 或 docker。")
        }
    }
}

/** deploy 过程中的一步：Before launch 的构建，或 deploy 自己。 */
private data class DeployStep(
    /** `beforeLaunch` 或 `deploy`。 */
    val kind: String,
    val name: String,
    /** 这一步自己的日志文件（绝对路径）；还没产出时为 null。 */
    val logFile: String?,
    val exitCode: Int?,
    val enabled: Boolean,
    /** 日志尾部预览（只有 Before launch 步骤读；deploy 自己的日志走 `fullOutputPath`）。 */
    val output: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind)
        put("name", name)
        put("logFile", logFile)
        put("exitCode", exitCode)
        put("enabled", enabled)
        put("output", output)
    }
}

private data class RemoteProcessItem(
    val configurationName: String,
    val server: String,
    val deployment: String?,
    val kind: String,
    val status: String,
    val pid: Long?,
    val container: String?,
    val workingDirectory: String?,
    val logDir: String?,
    val error: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("configurationName", configurationName)
        put("server", server)
        deployment?.let { put("deployment", it) }
        put("kind", kind)
        put("status", status)
        pid?.let { put("pid", it) }
        container?.let { put("container", it) }
        workingDirectory?.let { put("workingDirectory", it) }
        logDir?.let { put("logDir", it) }
        error?.let { put("error", it) }
    }
}
