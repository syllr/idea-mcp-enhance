package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.RunManager
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

        if (outcome.failure != null) {
            return McpToolCallResult.error(
                "部署失败：${outcome.failure}",
                buildJsonObject {
                    put("configurationName", configuration.name)
                    outcome.fullOutputPath?.let { put("fullOutputPath", it) }
                },
            )
        }

        val summary = buildString {
            append('[').append(configuration.name).append("] 已部署")
            append(if (outcome.started) "，Run 标签页已打开" else "，启动结果未知")
            outcome.pid?.let { append("，pid ").append(it) }
            outcome.fullOutputPath?.let { append("\n日志: ").append(it) }
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
                put("output", outcome.output)
            },
        )
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
