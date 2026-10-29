package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

/**
 * 不依赖任何产品插件的 MCP 工具骨架：异常兜底、参数错误、项目解析。
 *
 * 放在 `mcpserver.xml` 这一层的 config-file 里用（例如 `get_project_env`）——
 * 它不能引用 Terminal / remote-run / webDeployment 的任何类型。
 */
internal abstract class BaseMcpTool : McpTool {

    final override suspend fun call(args: JsonObject): McpToolCallResult = try {
        body(args)
    } catch (t: Throwable) {
        val detail = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
        McpToolCallResult.error("[internal_error] Tool ${descriptor.name} failed: $detail", null)
    }

    protected abstract suspend fun body(arguments: JsonObject): McpToolCallResult

    protected suspend fun currentProject(): Project? = coroutineContext.projectOrNull

    protected fun invalidArgument(message: String): McpToolCallResult =
        McpToolCallResult.error("[invalid_argument] $message", null)
}
