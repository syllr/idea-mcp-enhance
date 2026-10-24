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
 * 本地终端工具（`project_sdk_terminal` / `terminal_read` / `terminal_interrupt`）。
 *
 * 注册在 `META-INF/terminal.xml`：只有 Terminal 插件存在时这个 provider 才会被加载，
 * 所以这里的类可以安全 import `org.jetbrains.plugins.terminal.*`。
 */
class TerminalToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(
        ProjectSdkTerminalTool(),
        TerminalReadTool(),
        TerminalInterruptTool(),
        ListTerminalsTool(),
    )
}

/**
 * 读 / 打断的远程分支。
 *
 * 远程终端实现需要 `remote-run` 插件，所以它在另一个 config-file 里注册；这里只放
 * 一个平台无关的接口，由远程 provider 在 `getTools()` 时挂上来。
 */
internal interface RemoteTerminalHandler {
    suspend fun read(
        project: Project,
        tab: String,
        tail: Int?,
        full: Boolean,
        maxChars: Int,
    ): TerminalReadResult
    suspend fun interrupt(project: Project, tab: String)
}

internal class TerminalToolRouter {
    @Volatile
    private var remoteHandler: RemoteTerminalHandler? = null

    fun registerRemote(handler: RemoteTerminalHandler) {
        remoteHandler = handler
    }

    fun remote(): RemoteTerminalHandler? = remoteHandler
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

/**
 * 单次工具调用最多等这么久，然后返回 `running: true` 让调用方用 `terminal_read` 收尾。
 *
 * 固定值、**不开放给调用方**：它只是"这次调用最多阻塞多久"，不是调用方能调的性能旋钮；
 * 暴露出去只会让人以为结果可以通过参数把控，而真正需要的是"返回后去读那个 tab"。
 */
internal const val TERMINAL_CALL_TIMEOUT_MS = 300_000

internal fun validateMaxChars(maxChars: Int): String? = when {
    maxChars < 1 -> "maxChars 必须 >= 1。"
    maxChars > TERMINAL_HARD_MAX_CHARS ->
        "maxChars 不能大于 $TERMINAL_HARD_MAX_CHARS。"
    else -> null
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
        val maxChars = arguments.optionalInt("maxChars", TERMINAL_DEFAULT_MAX_CHARS)
        validateMaxChars(maxChars)?.let { return invalidArgument(it) }

        val input = arguments.optionalString("input")
        val workingDirectory = arguments.optionalString("workingDirectory")
        val env = parseEnv(arguments)
        val tab = arguments.optionalString("tab")
        val newTab = arguments.optionalBoolean("newTab", false)

        val result = runInLocalTerminal(
            project = project,
            command = command,
            input = input,
            timeoutMs = TERMINAL_CALL_TIMEOUT_MS,
            workingDirectory = workingDirectory,
            env = env,
            maxChars = maxChars,
            tab = tab,
            newTab = newTab,
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
// terminal_read
// ---------------------------------------------------------------------------

private class TerminalReadTool : BaseTerminalTool() {

    override val descriptor: McpToolDescriptor = terminalReadDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return invalidArgument(
            "$TERMINAL_READ_TOOL_NAME: no project is bound to this MCP session.",
        )

        val target = arguments.requiredString("target")?.lowercase()
            ?: return invalidArgument("target 不能为空（local / remote）。")
        if (target != "local" && target != "remote") {
            return invalidArgument("target 只能是 \"local\" 或 \"remote\"。")
        }

        val tab = arguments.requiredString("tab")
            ?: return invalidArgument("tab 不能为空（使用 project_sdk_terminal 返回的 tab id）。")
        val tail = (arguments["tail"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (tail != null && tail < 1) {
            return invalidArgument("tail 必须 >= 1。")
        }
        val full = arguments.optionalBoolean("full", false)
        val maxChars = arguments.optionalInt("maxChars", TERMINAL_DEFAULT_MAX_CHARS)
        validateMaxChars(maxChars)?.let { return invalidArgument(it) }

        val result = if (target == "local") {
            readLocalTerminal(project, tab, tail, full, maxChars)
        } else {
            val handler = service<TerminalToolRouter>().remote()
                ?: return invalidArgument("远程终端支持不可用（remote-run 插件未启用）。")
            handler.read(project, tab, tail, full, maxChars)
        }
        return McpToolCallResult.text(formatReadSummary(result), result.toJson())
    }
}

// ---------------------------------------------------------------------------
// terminal_interrupt
// ---------------------------------------------------------------------------

private class TerminalInterruptTool : BaseTerminalTool() {

    override val descriptor: McpToolDescriptor = terminalInterruptDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return invalidArgument(
            "$TERMINAL_INTERRUPT_TOOL_NAME: no project is bound to this MCP session.",
        )

        val target = arguments.requiredString("target")?.lowercase()
            ?: return invalidArgument("target 不能为空（local / remote）。")
        if (target != "local" && target != "remote") {
            return invalidArgument("target 只能是 \"local\" 或 \"remote\"。")
        }
        val tab = arguments.requiredString("tab")
            ?: return invalidArgument("tab 不能为空（使用 project_sdk_terminal 返回的 tab id）。")

        val router = service<TerminalToolRouter>()
        val remote = if (target == "remote") {
            router.remote() ?: return invalidArgument("远程终端支持不可用（remote-run 插件未启用）。")
        } else {
            null
        }

        if (remote != null) remote.interrupt(project, tab) else interruptLocalTerminal(project, tab)

        // 打断之后调用方最想知道的正是"停没停"，所以顺手读一次轻量快照。
        val snapshot = if (remote != null) {
            remote.read(project, tab, tail = null, full = false, maxChars = 2_000)
        } else {
            readLocalTerminal(project, tab, tail = null, full = false, maxChars = 2_000)
        }
        return McpToolCallResult.text(
            "Sent Ctrl-C to $tab.\n" + formatReadSummary(snapshot),
            snapshot.toJson(),
        )
    }
}

private class ListTerminalsTool : BaseTerminalTool() {

    override val descriptor: McpToolDescriptor = listTerminalsDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return invalidArgument(
            "$LIST_TERMINALS_TOOL_NAME: no project is bound to this MCP session.",
        )

        val kind = arguments.optionalString("kind")?.lowercase() ?: "all"
        if (kind !in setOf("all", TerminalKind.ProjectSdk.wire, TerminalKind.Remote.wire)) {
            return invalidArgument("kind 只能是 all / mcp_project_sdk / mcp_remote。")
        }

        val groups = listTerminalTabs(project)
            .filter { kind == "all" || it.kind.wire == kind }
        return McpToolCallResult.text(formatTerminalList(groups), terminalGroupsToJson(groups))
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
