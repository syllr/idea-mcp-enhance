package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.coroutines.coroutineContext

/**
 * 本地终端工具（`project_sdk_terminal` / `terminal_io`）。
 *
 * 注册在 `META-INF/terminal.xml`：只有 Terminal 插件存在时这个 provider 才会被加载，
 * 所以这里的类可以安全 import `org.jetbrains.plugins.terminal.*`。
 */
class TerminalToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(
        ProjectSdkTerminalTool(),
        TerminalIoTool(),
        ListTerminalsTool(),
    )
}

/**
 * `terminal_io` 的远程分支。
 *
 * 远程终端实现需要 `remote-run` 插件，所以它在另一个 config-file 里注册；这里只放
 * 一个平台无关的接口，由远程 provider 在 `getTools()` 时挂上来。
 */
internal interface RemoteTerminalIoHandler {
    suspend fun read(
        project: Project,
        tab: String,
        tail: Int?,
        full: Boolean,
        timeoutMs: Int,
        maxChars: Int,
    ): TerminalReadResult
    suspend fun write(project: Project, tab: String, text: String)
}

internal class TerminalIoRouter {
    @Volatile
    private var remoteHandler: RemoteTerminalIoHandler? = null

    fun registerRemote(handler: RemoteTerminalIoHandler) {
        remoteHandler = handler
    }

    fun remote(): RemoteTerminalIoHandler? = remoteHandler
}

internal abstract class BaseTerminalTool : McpTool {

    final override suspend fun call(args: JsonObject): McpToolCallResult = try {
        body(args)
    } catch (e: TerminalAccessException) {
        // 错误结果没有 structuredContent；code 必须进 text，agent 才能区分失败类型。
        McpToolCallResult.error(
            withErrorCode(e.code, e.message),
            terminalErrorJson(e.code, e.current, e.fix),
        )
    } catch (t: Throwable) {
        val detail = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
        McpToolCallResult.error(
            withErrorCode("internal_error", "Tool ${descriptor.name} failed: $detail"),
            null,
        )
    }

    protected abstract suspend fun body(arguments: JsonObject): McpToolCallResult

    protected suspend fun currentProject(): Project? = coroutineContext.projectOrNull

    protected fun invalidArgument(message: String): McpToolCallResult =
        McpToolCallResult.error(
            withErrorCode("invalid_argument", message),
            terminalErrorJson("invalid_argument", null, emptyList()),
        )
}

/** 错误结果没有 structuredContent，把 code 前置到可读文案里。 */
private fun withErrorCode(code: String, message: String): String = "[$code] $message"

private fun terminalErrorJson(code: String, current: String?, fix: List<String>): JsonObject = buildJsonObject {
    put("code", JsonPrimitive(code))
    if (current != null) put("current", JsonPrimitive(current))
    if (fix.isNotEmpty()) {
        put("fix", buildJsonArray { fix.forEach { add(JsonPrimitive(it)) } })
    }
}

internal fun validateTimeout(timeoutMs: Int): String? = when {
    timeoutMs < 0 -> "timeoutMs 不能小于 0。0 = 异步；> 0 = 最多等待的毫秒数。"
    timeoutMs > 12_000 -> "timeoutMs 不能大于 12000（MCP 会话约 15 秒，留余量）。"
    else -> null
}

internal fun validateMaxChars(maxChars: Int): String? = when {
    maxChars < 1 -> "maxChars 必须 >= 1。"
    maxChars > TERMINAL_HARD_MAX_CHARS ->
        "maxChars 不能大于 $TERMINAL_HARD_MAX_CHARS。"
    else -> null
}

/**
 * 终端工具专用的 timeoutMs 解析：**0 是合法值**（异步），不能沿用
 * `optionalInt` 的「<=0 回落默认」语义。
 */
internal fun JsonObject.terminalTimeout(default: Int): Int {
    val element = this["timeoutMs"] ?: return default
    if (element is JsonNull) return default
    val primitive = element as? JsonPrimitive ?: return default
    return primitive.content.toIntOrNull() ?: default
}

private fun parseEnv(arguments: JsonObject): Map<String, String> {
    val env = arguments["env"] as? JsonObject ?: return emptyMap()
    return env.mapNotNull { (key, value) ->
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        if (!primitive.isString) return@mapNotNull null
        key to primitive.content
    }.toMap()
}

// ---------------------------------------------------------------------------
// project_sdk_terminal
// ---------------------------------------------------------------------------

private class ProjectSdkTerminalTool : BaseTerminalTool() {

    override val descriptor: McpToolDescriptor = projectSdkTerminalDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return invalidArgument(
            "$PROJECT_SDK_TERMINAL_TOOL_NAME: no project is bound to this MCP session.",
        )

        val command = arguments.requiredString("command")
            ?: return invalidArgument("command 不能为空。")
        val timeoutMs = arguments.terminalTimeout(10_000)
        validateTimeout(timeoutMs)?.let { return invalidArgument(it) }
        val maxChars = arguments.optionalInt("maxChars", TERMINAL_DEFAULT_MAX_CHARS)
        validateMaxChars(maxChars)?.let { return invalidArgument(it) }

        val input = arguments.optionalString("input")
        val workingDirectory = arguments.optionalString("workingDirectory")
        val env = parseEnv(arguments)

        val result = runInLocalTerminal(
            project = project,
            command = command,
            input = input,
            timeoutMs = timeoutMs,
            workingDirectory = workingDirectory,
            env = env,
            maxChars = maxChars,
        )
        return McpToolCallResult.text(
            formatRunSummary(result),
            result.toJson(),
        )
    }
}

internal fun formatRunSummary(result: TerminalRunResult): String = buildString {
    append("[tab ").append(result.tab)
    append(", exitCode ").append(result.exitCode?.toString() ?: "null")
    if (result.timedOut) append(", timedOut")
    if (result.truncated) append(", truncated")
    append("]\n")
    append(result.text.ifEmpty { "(no output yet)" })
    if (result.warning != null) append("\n[warning] ").append(result.warning)
}

// ---------------------------------------------------------------------------
// terminal_io
// ---------------------------------------------------------------------------

private class TerminalIoTool : BaseTerminalTool() {

    override val descriptor: McpToolDescriptor = terminalIoDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return invalidArgument(
            "$TERMINAL_IO_TOOL_NAME: no project is bound to this MCP session.",
        )

        val target = arguments.requiredString("target")?.lowercase()
            ?: return invalidArgument("target 不能为空（local / remote）。")
        if (target != "local" && target != "remote") {
            return invalidArgument("target 只能是 \"local\" 或 \"remote\"。")
        }

        val tab = arguments.requiredString("tab")
            ?: return invalidArgument("tab 不能为空（使用 project_sdk_terminal 返回的 tab id）。")
        val action = arguments.requiredString("action")?.lowercase()
            ?: return invalidArgument("action 不能为空（read / write）。")

        return when (action) {
            "write" -> {
                val text = arguments.optionalString("text")
                    ?: return invalidArgument("action = write 时 text 必填。")
                if (target == "local") {
                    writeLocalTerminal(project, tab, text)
                } else {
                    val handler = service<TerminalIoRouter>().remote()
                        ?: return invalidArgument("远程终端支持不可用（remote-run 插件未启用）。")
                    handler.write(project, tab, text)
                }
                val result = TerminalReadResult(tab, null, null, null, false)
                McpToolCallResult.text("Wrote ${text.length} character(s) to $tab.", result.toJson())
            }

            "read" -> {
                val tail = (arguments["tail"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (tail != null && tail < 1) {
                    return invalidArgument("tail 必须 >= 1。")
                }
                val full = arguments.optionalBoolean("full", false)
                val timeoutMs = arguments.terminalTimeout(0)
                validateTimeout(timeoutMs)?.let { return invalidArgument(it) }
                val maxChars = arguments.optionalInt("maxChars", TERMINAL_DEFAULT_MAX_CHARS)
                validateMaxChars(maxChars)?.let { return invalidArgument(it) }

                val result = if (target == "local") {
                    readLocalTerminal(project, tab, tail, full, timeoutMs, maxChars)
                } else {
                    val handler = service<TerminalIoRouter>().remote()
                        ?: return invalidArgument("远程终端支持不可用（remote-run 插件未启用）。")
                    handler.read(project, tab, tail, full, timeoutMs, maxChars)
                }
                McpToolCallResult.text(formatReadSummary(result), result.toJson())
            }

            else -> invalidArgument("action 只能是 \"read\" 或 \"write\"。")
        }
    }
}

private class ListTerminalsTool : BaseTerminalTool() {

    override val descriptor: McpToolDescriptor = listTerminalsDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return invalidArgument(
            "$LIST_TERMINALS_TOOL_NAME: no project is bound to this MCP session.",
        )

        val includeUnmanaged = arguments.optionalBoolean("includeUnmanaged", true)
        val kind = arguments.optionalString("kind")?.lowercase() ?: "all"
        if (kind !in setOf("all", TerminalKind.ProjectSdk.wire, TerminalKind.Remote.wire, TerminalKind.User.wire)) {
            return invalidArgument("kind 只能是 all / mcp_project_sdk / mcp_remote / user。")
        }

        val tabs = listTerminalTabs(project, includeUnmanaged)
            .filter { kind == "all" || it.kind.wire == kind }
            .sortedWith(
                compareByDescending<TerminalTabInfo> { if (it.tab != null) 1 else 0 }
                    .thenByDescending { it.lastUsedAt ?: it.createdAt ?: 0L },
            )
        return McpToolCallResult.text(formatTerminalList(tabs), terminalTabsToJson(tabs))
    }
}

private fun formatReadSummary(result: TerminalReadResult): String = buildString {
    append("[tab ").append(result.tab)
    if (result.running == true) append(", running")
    if (result.truncated) append(", truncated")
    append("]\n")
    val commands = result.commands
    if (commands != null) {
        commands.forEachIndexed { index, segment ->
            if (index > 0) append("\n")
            append("$ ").append(segment.command).append("\n")
            append(segment.output.ifEmpty { "(no output)" })
        }
    } else {
        append(result.text.orEmpty().ifEmpty { "(no new output)" })
    }
}
