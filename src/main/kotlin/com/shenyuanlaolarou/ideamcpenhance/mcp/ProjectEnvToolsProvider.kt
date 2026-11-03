package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.openapi.components.service
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 项目环境变量工具（`get_project_env`）。
 *
 * 注册在 `META-INF/env.xml`：不依赖 Terminal 插件，也不依赖 remote-run —— 它只读
 * `ProjectTerminalEnvRegistry`，而那个 registry 是 `mcpserver.xml` 里的 project service。
 *
 * 调用方是 `opencode-idea` 的 `shell.hook("create.before")`，不是模型。
 */
class ProjectEnvToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(GetProjectEnvTool())
}

private class GetProjectEnvTool : BaseMcpTool() {

    override val descriptor: McpToolDescriptor = getProjectEnvDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return invalidArgument(
            "$GET_PROJECT_ENV_TOOL_NAME: no project is bound to this MCP session.",
        )

        val env = project.service<ProjectTerminalEnvRegistry>().aggregate(project)
        val payload = buildJsonObject {
            env.toJson().forEach { (key, value) -> put(key, value) }
            put("projectPath", project.basePath.orEmpty())
        }
        return McpToolCallResult.text(
            buildString {
                append("[PROJECT] ").append(project.basePath.orEmpty())
                append('\n').append("[PATH] ").append(env.path.ifEmpty { "(none)" })
                env.variables.forEach { (key, value) ->
                    append('\n').append('[').append(key).append("] ").append(value)
                }
            },
            payload,
        )
    }
}
