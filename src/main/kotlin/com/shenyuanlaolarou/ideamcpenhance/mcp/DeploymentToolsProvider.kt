package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * 把 SSH 配置与 Deployment 的能力挂到 IDE 内置的 MCP Server 上。
 *
 * 两个工具都是 **OpenCode 的数据通道**，不进模型可见的工具表：
 *
 * - `get_ssh_servers`：SSH 配置 + 凭据（`remote_exec` 用它连机器）
 * - `sync_deployments`：`hosts.json` → IDE 的 Deployment 面板
 *
 * ## 前提条件由链式 optional 依赖把守（不是运行时检查）
 *
 * ```
 * plugin.xml     → mcpserver.xml    （com.intellij.mcpServer）
 * mcpserver.xml  → deployment.xml   （com.jetbrains.plugins.webDeployment）
 * ```
 *
 * 只有两个插件都在时 `deployment.xml` 才会被读取、本 provider 才会注册。
 */
class DeploymentToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(GetSshServersTool(), SyncDeploymentsTool())
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
    } catch (e: DeploymentConfigException) {
        // 配置问题得让调用方转给用户去改，不能包成 internal_error。
        McpToolCallResult.error("[deployment_config_incomplete] ${e.message}", null)
    } catch (e: Throwable) {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
        McpToolCallResult.error("Tool ${descriptor.name} failed: $detail", null)
    }

    protected abstract suspend fun body(arguments: JsonObject): McpToolCallResult

    /** 取当前 MCP 调用所属的项目。项目被关掉 / 会话没有绑定项目时返回 null。 */
    protected suspend fun currentProject(): Project? = coroutineContext.projectOrNull

    protected fun invalidArgument(message: String): McpToolCallResult =
        McpToolCallResult.error("[invalid_argument] $message", null)
}

// ---------------------------------------------------------------------------
// get_ssh_servers（凭据通道，调用方是 opencode-idea 的 remote_exec）
// ---------------------------------------------------------------------------

private class GetSshServersTool : BaseDeploymentTool() {

    override val descriptor: McpToolDescriptor = getSshServersDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return McpToolCallResult.error(
            "$GET_SSH_SERVERS_TOOL_NAME: no project is bound to this MCP session.",
            null,
        )

        val servers = sshServersWithCredentials(project)
        val report = DeploymentCredentialsReport(servers)
        // summary 里绝不能出现凭据字段：它是唯一会稳定到达调用方日志 / 模型的部分，
        // 密码只在 structuredContent 里，由 opencode-idea 直接消费。
        val summary = buildString {
            append("Found ").append(report.servers.size).append(" SSH configuration(s) with credentials:\n")
            report.servers.forEach { server ->
                append("- ").append(server.name)
                append(' ').append(server.host).append(':').append(server.port)
                append(' ').append(server.username)
                append(" [").append(server.authType).append("]\n")
            }
        }.trimEnd()
        return McpToolCallResult.text(summary, report.toJson())
    }
}

// ---------------------------------------------------------------------------
// sync_deployments（hosts.json → IDE 的 Deployment 面板）
// ---------------------------------------------------------------------------

private class SyncDeploymentsTool : BaseDeploymentTool() {

    override val descriptor: McpToolDescriptor = syncDeploymentsDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return McpToolCallResult.error(
            "$SYNC_DEPLOYMENTS_TOOL_NAME: no project is bound to this MCP session.",
            null,
        )

        val server = arguments["server"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (server.isEmpty()) return invalidArgument("server 不能为空。")

        val rawDeployments = arguments["deployments"]?.let { element ->
            runCatching { element.jsonArray }.getOrNull()
        } ?: return invalidArgument("deployments 必须是数组。")

        val requests = mutableListOf<DeploymentSyncRequest>()
        for (element in rawDeployments) {
            val record = runCatching { element.jsonObject }.getOrNull()
                ?: return invalidArgument("deployments 里有不是对象的条目。")
            val request = parseDeployment(record) ?: return invalidArgument(
                "deployment 缺少 name / localPath / deployPath，或有非法的 exclude。",
            )
            requests += request
        }

        val report = syncDeployments(project, server, requests)
        val summary = buildString {
            append("Synced ").append(requests.size).append(" deployment(s) to server ").append(server)
            append(": created=").append(report.created.size)
            append(", updated=").append(report.updated.size)
            append(", unchanged=").append(report.unchanged.size)
            if (report.orphaned.isNotEmpty()) {
                append(", orphaned=").append(report.orphaned.joinToString(", "))
            }
        }
        return McpToolCallResult.text(summary, report.toJson())
    }

    private fun parseDeployment(record: JsonObject): DeploymentSyncRequest? {
        val name = record["name"]?.jsonPrimitive?.content?.trim().orEmpty()
        val localPath = record["localPath"]?.jsonPrimitive?.content?.trim().orEmpty()
        val deployPath = record["deployPath"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (name.isEmpty() || localPath.isEmpty() || deployPath.isEmpty()) return null

        val exclude = record["exclude"]?.let { element ->
            runCatching { element.jsonArray }.getOrNull()
        } ?: JsonArray(emptyList())
        val paths = exclude.map { it.jsonPrimitive.content.trim() }.filter { it.isNotEmpty() }
        // 和 hosts.json 的契约一致：只接受路径前缀与精确文件，不认通配。
        if (paths.any { it.contains('*') || it.contains('?') }) return null

        return DeploymentSyncRequest(name, localPath, deployPath, paths)
    }
}
