package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolInvocationMode
import com.intellij.mcpserver.impl.McpServerService
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 屏蔽内置的 `execute_terminal_command`。
 *
 * 它和我们的 `project_sdk_terminal` 功能重叠，但内置工具用 `shell -c`，不加载登录/交互 shell，
 * 也不读 Terminal 设置里的环境变量——留着它只会让 agent 选错、继续踩环境变量的坑。
 */
class TerminalToolFilterProvider : McpToolFilterProvider {

    override fun applyFilters(
        context: McpToolFilterProvider.McpToolFilterContext,
        clientInfo: Implementation?,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode,
    ) {
        context.updateState(McpToolFilterProvider.McpToolState(enabled = false, routerOnly = false)) { tool ->
            tool.descriptor.name == "execute_terminal_command"
        }
    }

    override fun getUpdates(
        clientInfo: Implementation?,
        scope: CoroutineScope,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode,
    ): Flow<Unit> = emptyFlow()
}
