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

    override fun getTools(): List<McpTool> = listOf(GetDeploymentServersTool())
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
// get_deployment_servers（凭据通道，调用方是 opencode-idea 的 remote_exec）
// ---------------------------------------------------------------------------

private class GetDeploymentServersTool : BaseDeploymentTool() {

    override val descriptor: McpToolDescriptor = getDeploymentServersDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return McpToolCallResult.error(
            "$GET_DEPLOYMENT_SERVERS_TOOL_NAME: no project is bound to this MCP session.",
            null,
        )

        val servers = try {
            deploymentServersWithCredentials(project)
        } catch (e: DeploymentConfigException) {
            // 配置问题得让调用方转给用户去改，不能包成 internal_error。
            return McpToolCallResult.error("[deployment_config_incomplete] ${e.message}", null)
        }
        val report = DeploymentCredentialsReport(servers)
        // summary 里绝不能出现凭据字段：它是唯一会稳定到达调用方日志 / 模型的部分，
        // 密码只在 structuredContent 里，由 opencode-idea 直接消费。
        val summary = buildString {
            append("Found ").append(report.servers.size).append(" SFTP server(s) with credentials:\n")
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
