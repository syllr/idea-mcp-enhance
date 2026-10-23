package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

/**
 * 远程终端工具（`execute_in_remote_host`）。
 *
 * 注册在 `META-INF/remote-terminal.xml`：只有 `org.jetbrains.plugins.remote-run` 存在时
 * 这个 provider 才会被加载，所以这里的类可以安全 import `com.jetbrains.plugins.remotesdk.*`。
 */
class RemoteTerminalToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> {
        // terminal_io 的远程分支挂到共享 router 上
        service<TerminalIoRouter>().registerRemote(RemoteIoHandler)
        return listOf(ExecuteInRemoteHostTool())
    }
}

private object RemoteIoHandler : RemoteTerminalIoHandler {
    override suspend fun read(
        project: Project,
        tab: String,
        tail: Int?,
        full: Boolean,
        timeoutMs: Int,
        maxChars: Int,
    ): TerminalReadResult = readRemoteTerminal(project, tab, tail, full, timeoutMs, maxChars)

    override suspend fun write(project: Project, tab: String, text: String) {
        writeRemoteTerminal(project, tab, text)
    }
}

private class ExecuteInRemoteHostTool : BaseTerminalTool() {

    override val descriptor: McpToolDescriptor = executeInRemoteHostDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return invalidArgument(
            "$EXECUTE_REMOTE_TOOL_NAME: no project is bound to this MCP session.",
        )

        val serverName = arguments.requiredString("serverName")
            ?: return invalidArgument("serverName 不能为空。")
        val command = arguments.requiredString("command")
            ?: return invalidArgument("command 不能为空。")
        val timeoutMs = arguments.terminalTimeout(10_000)
        validateTimeout(timeoutMs)?.let { return invalidArgument(it) }
        val maxChars = arguments.optionalInt("maxChars", TERMINAL_DEFAULT_MAX_CHARS)
        validateMaxChars(maxChars)?.let { return invalidArgument(it) }

        val input = arguments.optionalString("input")
        val workingDirectory = arguments.optionalString("workingDirectory")
        val loginShell = arguments.optionalBoolean("loginShell", true)
        val strictHostKey = arguments.optionalBoolean("strictHostKey", false)

        val result = runInRemoteTerminal(
            project = project,
            serverName = serverName,
            command = command,
            input = input,
            timeoutMs = timeoutMs,
            workingDirectory = workingDirectory,
            loginShell = loginShell,
            strictHostKey = strictHostKey,
            maxChars = maxChars,
        )
        return McpToolCallResult.text(formatRunSummary(result), result.toJson())
    }
}
