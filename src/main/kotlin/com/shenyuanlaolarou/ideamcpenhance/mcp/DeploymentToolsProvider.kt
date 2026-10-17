package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.projectOrNull
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
 * ## 前提条件由**链式 optional 依赖**把守（不是运行时检查）
 *
 * ```
 * plugin.xml     → mcpserver.xml    （com.intellij.mcpServer）
 * mcpserver.xml  → deployment.xml   （com.jetbrains.plugins.webDeployment）
 * ```
 *
 * 平台支持这样链：`PluginDescriptorLoader.loadPluginDependencyDescriptors` 解析出 config-file 后，
 * 会对那个子描述符**递归调用自己**（并带 `checkCycle` 防环）。于是只有两个插件**都在**时，
 * `deployment.xml` 才会被读取、本 provider 才会被注册。实测（禁用 webDeployment 后启动）平台日志：
 *
 * ```
 * plugin 'FTP/SFTP/WebDAV Connectivity' (...) is marked disabled
 *   └ ... <depends> config 'deployment.xml' of plugin com.shenyuanlaolarou.ideamcpenhance excluded
 * ```
 *
 * 为什么不把这条依赖直接写在 `plugin.xml`：
 *   ① 裸写 `<depends optional="true">`（不带 config-file）会被 Marketplace 的结构校验警告
 *      "Optional dependency declaration ... should specify config-file"；
 *   ② 直接在 `plugin.xml` 里配 config-file，会要求 **MCP Server 与 webDeployment 同时存在**
 *      才能解析 `mcpServer.*` 扩展点命名空间——MCP Server 缺失时插件反而加载失败。
 * 挂在 `mcpserver.xml` 之下就避开了这两个问题：命名空间的前提由上一层保证。
 *
 * ## 为什么**没有**运行时存在性检查（原先有，已删）
 *
 * 两个理由，任一条都足够：
 *   1. **冗余**：config-file 已经把"插件不在"这件事挡在注册之前了（见上面的实测日志）。
 *   2. **没有合规的 API**：`PluginManagerCore.getPlugin` 与 `PluginManager.findEnabledPlugin`
 *      **都是 `@ApiStatus.Internal`**（后者是 Plugin Verifier 实测报出来的），而本仓库硬规则
 *      禁止使用内部 API。既然前一条已经保证正确性，就不该为"第二道防线"去踩这条规则。
 *
 * 于是 [getTools] 无条件返回四个工具——provider 存在 ⇒ webDeployment 一定在。
 *
 * 平台接线的引用全部集中在 `DeploymentAccess` 一个文件里，是为了让这件事可推理。
 */
class DeploymentToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(
        ListDeploymentServersTool(),
        UploadToDeploymentTool(),
        DownloadFromDeploymentTool(),
        GetDeploymentTaskStatusTool(),
    )
}

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

        // summary 必须自带「有哪些服务器、哪台是默认」：这两条是调用方决定**要不要传 serverName**
        // 的前提（不传就落到默认那台），而完整字段只在 structuredContent 里 —— 客户端是否读它不可控，
        // 只有 text 会稳定到达。把它们塞进 text，调用方就不必依赖 structuredContent 才能做选择。
        //
        // (default) 标记打在每台自己身上，而不是只在首行写一次名字：这样台数一多也不用把首行和
        // 列表逐个对名字，名字相近或重名时不会看错。
        val summary = when {
            servers.isEmpty() ->
                "No deployment servers are configured for this project."

            else -> {
                // 默认服务器是**组**（而不是某台具体服务器）时，没有哪台能挂 (default)，
                // 所以这种情况要在首行说出来 —— 否则「默认是谁」这一信息就整个丢了。
                val defaultClause = when {
                    defaultName == null -> " no default server or group is set."
                    servers.none { it.name == defaultName } -> " the default is a group named \"$defaultName\"."
                    else -> ""
                }
                buildString {
                    append("Found ").append(servers.size).append(" deployment server(s);")
                    append(defaultClause).append('\n')
                    servers.forEach { server ->
                        append("- \"").append(server.name.ifEmpty { "(unnamed)" }).append('"')
                        if (server.name == defaultName) append(" (default)")
                        append('\n')
                    }
                }.trimEnd('\n')
            }
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
        val roots = pathMappingsOf(config, server)
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
        // 失败时不能沿用成功措辞：平台只给一个 succeeded=false，连 failed 计数都可能是 0
        // （实测连不上主机时就是 "0 operation(s)"），读起来跟"传完了"一模一样。
        val summary = if (outcome.succeeded) {
            buildString {
                append("Uploaded to \"").append(outcome.serverName).append("\" (mode=").append(mode).append("): ")
                append(outcome.totalProcessed).append(" operation(s)")
                if (deleted > 0) append(", removed remote extras in ").append(deleted).append(" delete operation(s)")
                if (outcome.failed > 0) append(", ").append(outcome.failed).append(" failed")
                append(" in ").append(outcome.elapsedMs).append("ms. handle=").append(outcome.handle)
            }
        } else {
            buildString {
                append("Upload to \"").append(outcome.serverName).append("\" (mode=").append(mode).append(") FAILED ")
                appendFailureProgress(outcome)
                append(". ").append(failureReason(outcome))
                append(" handle=").append(outcome.handle)
            }
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

/**
 * 失败原因的一句话。
 *
 * 平台那条路径只给一个 `succeeded = false`：`doRun` 返回布尔值，具体原因（连不上、权限、
 * 远端路径解析失败…）它自己记在 Deployment 日志里，不经过我们的返回结构。所以这里优先用
 * 任务自己填的 warning，没有就指向日志 —— 不臆测原因。
 */
private fun failureReason(outcome: DeploymentTaskOutcome): String =
    outcome.warnings.firstOrNull()
        ?: "The platform reported no detail; the IDE's Deployment log has the cause."

/** 失败时描述"跑到哪一步"：一个操作都没跑，和"跑了 N 个之后失败"是两回事。 */
private fun StringBuilder.appendFailureProgress(outcome: DeploymentTaskOutcome) {
    if (outcome.totalProcessed > 0) {
        append("after ").append(outcome.totalProcessed).append(" operation(s)")
        if (outcome.failed > 0) append(", of which ").append(outcome.failed).append(" failed")
    } else {
        append("before any operation ran")
    }
}

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
        // 与 upload 同一条规则：失败时不能说 "finished"，否则 "0 operation(s)" 会被读成成功。
        val summary = if (outcome.succeeded) {
            buildString {
                append(outcome.kind.replaceFirstChar { it.uppercase() }).append(' ').append(outcome.handle)
                append(" \"").append(outcome.serverName).append("\" finished in ")
                append(outcome.elapsedMs).append("ms: ").append(outcome.totalProcessed).append(" operation(s)")
                if (deleted > 0) append(", removed remote extras in ").append(deleted).append(" delete operation(s)")
                if (outcome.failed > 0) append(", ").append(outcome.failed).append(" failed")
                append('.')
            }
        } else {
            buildString {
                append(outcome.kind.replaceFirstChar { it.uppercase() }).append(' ').append(outcome.handle)
                append(" \"").append(outcome.serverName).append("\" FAILED ")
                appendFailureProgress(outcome)
                append(". ").append(failureReason(outcome))
            }
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
        val roots = pathMappingsOf(publishConfigOf(project), server).mapNotNull { mapping ->
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
