package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.ide.plugins.PluginManager
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.jetbrains.plugins.webDeployment.config.PublishConfig
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

/**
 * 把 Deployment（FTP/SFTP/WebDAV Connectivity）的能力挂到 IDE 内置的 MCP Server 上。
 *
 * ## 为什么单独一个 provider，而不是并进 `RunLifecycleToolsProvider`
 *
 * 两者的前置条件不同：Run Lifecycle 只需要 MCP Server；Deployment 还额外需要
 * `com.jetbrains.plugins.webDeployment` 这个**只在 Ultimate 里存在**的插件
 * （它依赖 `com.intellij.modules.ultimate`）。
 *
 * ## 为什么用运行时检查，而不是再开一个 optional 依赖的 config-file
 *
 * 另一个做法是在 `plugin.xml` 里写
 * `<depends optional="true" config-file="deployment.xml">com.jetbrains.plugins.webDeployment</depends>`，
 * 再把 provider 注册在那个文件里。没有采用，因为那样要求 **MCP Server 与 webDeployment 同时存在**
 * 才能解析文件里的扩展点命名空间；MCP Server 缺失时反而会让插件加载失败。
 *
 * 运行时检查没有这个问题：webDeployment 不在时 [getTools] 直接返回空列表，
 * 工具不出现在 `tools/list` 里，插件其余部分照常工作。
 *
 * ## 类加载隔离（这里的关键）
 *
 * 引用 `com.jetbrains.plugins.webDeployment.*` 的类**只能在确认插件存在之后**才被加载。
 * JVM 是按方法惰性解析引用类的（HotSpot 的 split verifier），所以只要
 * [ListDeploymentServersTool] 是在 [deploymentPluginAvailable] 通过之后才被 `new` 出来，
 * 插件缺失时那条 `new` 就永远不会执行，也就永远不会去解析它引用的那些类。
 *
 * 这正是本仓库对 MCP Server 那条铁律的镜像写法：**引用可选插件的类，必须由运行时的存在性检查把守**。
 * 平台接线的引用全部集中在 `DeploymentAccess` 一个文件里，就是为了让这件事可推理。
 */
class DeploymentToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> =
        if (deploymentPluginAvailable()) {
            listOf(
                ListDeploymentServersTool(),
                UploadToDeploymentTool(),
                DownloadFromDeploymentTool(),
                GetDeploymentTaskStatusTool(),
            )
        } else {
            emptyList()
        }
}

/** Deployment 插件（FTP/SFTP/WebDAV Connectivity）的 ID。大小写敏感。 */
private const val DEPLOYMENT_PLUGIN_ID = "com.jetbrains.plugins.webDeployment"

/**
 * 这个 IDE 上有没有**已加载并启用**的 Deployment 插件。
 *
 * 用 [PluginManager.findEnabledPlugin] 而不是 `PluginManagerCore.getPlugin`：后者被标了
 * `@ApiStatus.Internal`（本仓库禁用内部 API，见 AGENTS.md）。前者是公开 API，而且语义更准——
 * 我们要的是"能用"，不只是"装过"（装了但被禁用同样不行）。
 *
 * 判断本身不引用 webDeployment 的任何类型，所以可以安全地在类加载前调用——这正是下面那套
 * 类加载隔离的前提。
 */
private fun deploymentPluginAvailable(): Boolean =
    PluginManager.getInstance().findEnabledPlugin(PluginId.getId(DEPLOYMENT_PLUGIN_ID)) != null

/**
 * 工具共用的骨架：兜住异常 → 跑具体逻辑。
 *
 * 与 `BaseRunTool` 同样的理由：MCP 工具把异常抛出去，agent 拿到的是不可读的失败；
 * 统一转成 [McpToolCallResult.error]，让失败至少是可读的结构化结果。
 */
private abstract class BaseDeploymentTool : McpTool {

    // 参数名与父接口保持一致（`McpTool.call(args)`），避免具名实参调用时对不上。
    final override suspend fun call(args: JsonObject): McpToolCallResult = try {
        body(args)
    } catch (e: Throwable) {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
        McpToolCallResult.error("Tool ${descriptor.name} failed: $detail", null)
    }

    protected abstract suspend fun body(arguments: JsonObject): McpToolCallResult

    /** 取当前 MCP 调用所属的项目。项目被关掉 / 会话没有绑定项目时返回 null。 */
    protected suspend fun currentProject(): Project? = coroutineContext.projectOrNull
}

// ---------------------------------------------------------------------------
// list_deployment_servers
// ---------------------------------------------------------------------------

private class ListDeploymentServersTool : BaseDeploymentTool() {

    override val descriptor: McpToolDescriptor = listDeploymentServersDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return McpToolCallResult.error(
            "$LIST_DEPLOYMENT_SERVERS_TOOL_NAME: no project is bound to this MCP session.",
            null,
        )

        // 纯读：只读 IDE 已存的设置，不建连接、不碰网络。
        val servers = allDeploymentServers(project)
        val options = currentDeploymentOptions(project)
        val defaultName = currentDefaultServerOrGroupName(project)

        val report = DeploymentServersReport(
            defaultServerOrGroupName = defaultName,
            servers = servers,
            options = options,
            warnings = deploymentWarnings(defaultName, servers, options),
        )

        val summary = when {
            servers.isEmpty() ->
                "No deployment servers are configured for this project."

            defaultName == null ->
                "Found ${servers.size} deployment server(s), but no default server or group is set."

            else ->
                "Found ${servers.size} deployment server(s); default: \"$defaultName\"."
        }
        return McpToolCallResult.text(summary, report.toJson())
    }
}

// ---------------------------------------------------------------------------
// upload_to_deployment
// ---------------------------------------------------------------------------

private class UploadToDeploymentTool : BaseDeploymentTool() {

    override val descriptor: McpToolDescriptor = uploadToDeploymentDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return McpToolCallResult.error(
            "$UPLOAD_TO_DEPLOYMENT_TOOL_NAME: no project is bound to this MCP session.",
            null,
        )

        val requested = arguments.optionalString("serverName")
        val mode = arguments.optionalString("mode") ?: MODE_MIRROR
        if (mode != MODE_UPLOAD && mode != MODE_MIRROR) {
            return McpToolCallResult.error(
                "`mode` must be \"$MODE_UPLOAD\" or \"$MODE_MIRROR\" (got \"$mode\").",
                null,
            )
        }
        val waitMs = arguments.optionalInt("waitMs", DEFAULT_DEPLOYMENT_WAIT_MS.toInt()).toLong()

        val (server, resolveFailure) = resolveTargetServer(project, requested)
        if (server == null) {
            return McpToolCallResult.error(
                resolveFailure ?: "$UPLOAD_TO_DEPLOYMENT_TOOL_NAME: could not resolve a deployment server.",
                null,
            )
        }

        // 建连接之前先问清楚——尤其是凭据：没有它，连下去会弹模态登录框。
        connectionPreflightFailure(project, server)?.let { failure ->
            return McpToolCallResult.error(failure, null)
        }

        // 本次策略走**配置副本**：clone 一份、只改这次要覆盖的两项，用户的持久配置一个字节都不动。
        val config = runCatching { PublishConfig.getInstance(project).clone() }.getOrNull()
            ?: return McpToolCallResult.error("Could not read this project's deployment configuration.", null)

        val serverName = server.getName().orEmpty()
        val roots = runCatching { config.getPathMappings(serverName) }.getOrNull().orEmpty()
            .mapNotNull { mapping ->
                runCatching { mapping.localPath }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
        if (roots.isEmpty()) {
            return McpToolCallResult.error(
                "\"$serverName\" has no path mapping in this project, so there is nothing to upload. " +
                    "Add a Mapping in its Deployment settings.",
                null,
            )
        }

        config.isDeleteMissingItems = (mode == MODE_MIRROR)
        // 删除确认框在 MCP 路径上没人能点：必须关掉，否则 mirror 会把调用连同 EDT 一起卡住。
        config.isConfirmBeforeDeletion = false

        val outcome = uploadToServer(project, server, config, roots, mode, waitMs)

        val report = outcome.toReport(roots)

        // 还在传：绝不能报成成功。用 error 让 AI 明确看到"进行中"，并把句柄给它去查。
        if (outcome.state == DEPLOYMENT_STATE_IN_PROGRESS) {
            return McpToolCallResult.error(
                outcome.warnings.firstOrNull()
                    ?: "Upload to \"${outcome.serverName}\" is still in progress (handle ${outcome.handle}).",
                report.toJson(),
            )
        }

        // counter 的 key 是 WDBundle 的 message key（形如 `transfer.details.deleted`），
        // 不是裸的 `deleted` —— 用 contains 匹配，免得死记 key 名。
        //
        // ⚠️ 粒度是平台自己定的，**不是"文件数"**：实测删掉一个含 400 个文件的目录，`deleted` 也只是 1
        // （它数的是"删除操作"）。所以文案写"delete operation(s)"，不写 file(s)。
        val deleted = outcome.counters.entries.firstOrNull { it.key.contains("deleted") }?.value ?: 0
        val summary = buildString {
            append("Uploaded to \"").append(outcome.serverName).append("\" (mode=").append(mode).append("): ")
            append(outcome.totalProcessed).append(" operation(s)")
            if (deleted > 0) append(", removed remote extras in ").append(deleted).append(" delete operation(s)")
            if (outcome.failed > 0) append(", ").append(outcome.failed).append(" failed")
            append(" in ").append(outcome.elapsedMs).append("ms. handle=").append(outcome.handle)
        }
        return if (outcome.succeeded) {
            McpToolCallResult.text(summary, report.toJson())
        } else {
            McpToolCallResult.error(summary, report.toJson())
        }
    }
}

/**
 * 平台层结果 → 工具返回值。两个工具共用，保证 upload 与 status 返回**同一套字段**。
 *
 * 发起传输那次才知道本地根（事后查状态拿不到），所以由调用方传入。
 */
/**
 * 平台层结果 → 工具返回值。上传、下载、查状态三者共用，保证返回字段一致。
 *
 * [roots] 只有发起传输那次才知道（事后查状态拿不到），由调用方传入。
 */
private fun DeploymentTaskOutcome.toReport(roots: List<String>) = DeploymentTaskReport(
    handle = handle,
    kind = kind,
    serverName = serverName,
    mode = mode,
    roots = roots,
    state = state,
    elapsedMs = elapsedMs,
    processed = processed,
    total = total,
    succeeded = succeeded,
    totalProcessed = totalProcessed,
    failed = failed,
    counters = counters,
    warnings = warnings,
)

// ---------------------------------------------------------------------------
// get_deployment_task_status
// ---------------------------------------------------------------------------

/**
 * 查询**一次** deployment 传输（上传或下载）的进度。
 *
 * 纯只读：它**不会**启动任何传输。看到 `in_progress` 就继续查，看到 `completed` 收工。
 */
private class GetDeploymentTaskStatusTool : BaseDeploymentTool() {

    override val descriptor: McpToolDescriptor = getDeploymentTaskStatusDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return McpToolCallResult.error(
            "$GET_TASK_STATUS_TOOL_NAME: no project is bound to this MCP session.",
            null,
        )

        val handle = arguments.requiredString("handle") ?: return McpToolCallResult.error(
            "$GET_TASK_STATUS_TOOL_NAME: `handle` is required — pass the handle returned by " +
                "`upload_to_deployment` or `download_from_deployment`.",
            null,
        )

        val outcome = deploymentTaskStatusOf(project, handle) ?: return McpToolCallResult.error(
            "Unknown handle \"$handle\". It never existed in this project, or it was one of the oldest finished " +
                "transfers and has since been dropped (only the most recent 20 finished ones per project are kept).",
            null,
        )

        val report = outcome.toReport(emptyList())

        if (outcome.state == DEPLOYMENT_STATE_IN_PROGRESS) {
            val progress = if (outcome.total > 0) "${outcome.processed}/${outcome.total}" else "${outcome.processed}"
            return McpToolCallResult.text(
                "${outcome.kind} ${outcome.handle} \"${outcome.serverName}\" is still in progress " +
                    "(${progress} operations done, ${outcome.elapsedMs}ms so far). Query again in a few seconds.",
                report.toJson(),
            )
        }

        val deleted = outcome.counters.entries.firstOrNull { it.key.contains("deleted") }?.value ?: 0
        val summary = buildString {
            append(outcome.kind.replaceFirstChar { it.uppercase() }).append(' ').append(outcome.handle)
            append(" \"").append(outcome.serverName).append("\" finished in ")
            append(outcome.elapsedMs).append("ms: ").append(outcome.totalProcessed).append(" operation(s)")
            if (deleted > 0) append(", removed remote extras in ").append(deleted).append(" delete operation(s)")
            if (outcome.failed > 0) append(", ").append(outcome.failed).append(" failed")
            append('.')
        }
        return if (outcome.succeeded) {
            McpToolCallResult.text(summary, report.toJson())
        } else {
            McpToolCallResult.error(summary, report.toJson())
        }
    }
}

// ---------------------------------------------------------------------------
// download_from_deployment
// ---------------------------------------------------------------------------

private class DownloadFromDeploymentTool : BaseDeploymentTool() {

    override val descriptor: McpToolDescriptor = downloadFromDeploymentDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return McpToolCallResult.error(
            "$DOWNLOAD_TOOL_NAME: no project is bound to this MCP session.",
            null,
        )

        val requested = arguments.optionalString("serverName")
        val waitMs = arguments.optionalInt("waitMs", DEFAULT_DEPLOYMENT_WAIT_MS.toInt()).toLong()

        val (server, resolveFailure) = resolveTargetServer(project, requested)
        if (server == null) {
            return McpToolCallResult.error(
                resolveFailure ?: "$DOWNLOAD_TOOL_NAME: could not resolve a deployment server.",
                null,
            )
        }

        // 建连接之前先问清楚——尤其是凭据：没有它，连下去会弹模态登录框。
        connectionPreflightFailure(project, server)?.let { failure ->
            return McpToolCallResult.error(failure, null)
        }

        val serverName = server.getName().orEmpty()
        val roots = runCatching {
            publishConfigOf(project).getPathMappings(serverName)
        }.getOrNull().orEmpty().mapNotNull { mapping ->
            runCatching { mapping.localPath }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        if (roots.isEmpty()) {
            return McpToolCallResult.error(
                "\"$serverName\" has no path mapping in this project, so there is nothing to download. " +
                    "Add a Mapping in its Deployment settings.",
                null,
            )
        }

        val outcome = downloadFromServer(project, server, roots, waitMs)
        val report = outcome.toReport(roots)

        if (outcome.state == DEPLOYMENT_STATE_IN_PROGRESS) {
            return McpToolCallResult.error(
                outcome.warnings.firstOrNull()
                    ?: "Download from \"$serverName\" is still in progress (handle ${outcome.handle}).",
                report.toJson(),
            )
        }

        val summary = buildString {
            append("Downloaded from \"").append(serverName).append("\"")
            if (outcome.succeeded) {
                append(": done in ").append(outcome.elapsedMs).append("ms")
            } else {
                append(": NOT downloaded (").append(outcome.warnings.firstOrNull() ?: "unknown error").append(')')
            }
            append(". handle=").append(outcome.handle)
        }
        return if (outcome.succeeded) {
            McpToolCallResult.text(summary, report.toJson())
        } else {
            McpToolCallResult.error(summary, report.toJson())
        }
    }
}
