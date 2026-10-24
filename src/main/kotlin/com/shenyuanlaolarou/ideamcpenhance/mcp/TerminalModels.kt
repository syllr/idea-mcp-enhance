package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 终端工具的纯数据层：预检结果、执行结果、命令段，以及文本清洗 / 切段。
 *
 * 这一层不 import 任何平台类，单测是纯 JUnit。
 */

/** 预检结果。失败时带上机读 code、面向人的说明、修复步骤。 */
internal sealed interface TerminalPreflight {
    data object Ready : TerminalPreflight

    data class Blocked(
        val code: String,
        val message: String,
        val current: String? = null,
        val fix: List<String> = emptyList(),
    ) : TerminalPreflight
}

/** 预检失败 → 结构化 details（人类可读信息在 message 里）。 */
internal fun TerminalPreflight.Blocked.toJson(): JsonObject = buildJsonObject {
    put("code", JsonPrimitive(code))
    if (current != null) put("current", JsonPrimitive(current))
    if (fix.isNotEmpty()) {
        put("fix", buildJsonArray { fix.forEach { add(JsonPrimitive(it)) } })
    }
}

/** 终端 tab 的来源。`User` 表示用户自己打开、MCP 不可寻址的 tab。 */
internal enum class TerminalKind(val wire: String) {
    ProjectSdk("mcp_project_sdk"),
    Remote("mcp_remote"),
    User("user"),
}

/** `list_terminals` 的一条 tab 快照。 */
internal data class TerminalTabInfo(
    /** MCP 创建的 tab id；用户自己打开的 tab 没有 id。 */
    val tab: String?,
    val kind: TerminalKind,
    val addressable: Boolean,
    val status: String,
    val shell: String?,
    val workingDirectory: String?,
    val currentDirectory: String?,
    val serverName: String?,
    val lastCommand: String?,
    val lastCommandAt: Long?,
    val lastExitCode: Int?,
    val lastTimedOut: Boolean?,
    val createdAt: Long?,
    val lastUsedAt: Long?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tab", tab?.let(::JsonPrimitive) ?: JsonNull)
        put("kind", JsonPrimitive(kind.wire))
        put("addressable", JsonPrimitive(addressable))
        put("status", JsonPrimitive(status))
        put("shell", shell?.let(::JsonPrimitive) ?: JsonNull)
        put("workingDirectory", workingDirectory?.let(::JsonPrimitive) ?: JsonNull)
        put("currentDirectory", currentDirectory?.let(::JsonPrimitive) ?: JsonNull)
        put("serverName", serverName?.let(::JsonPrimitive) ?: JsonNull)
        put("lastCommand", lastCommand?.let(::JsonPrimitive) ?: JsonNull)
        put("lastCommandAt", lastCommandAt?.let(::JsonPrimitive) ?: JsonNull)
        put("lastExitCode", lastExitCode?.let(::JsonPrimitive) ?: JsonNull)
        put("lastTimedOut", lastTimedOut?.let(::JsonPrimitive) ?: JsonNull)
        put("createdAt", createdAt?.let(::JsonPrimitive) ?: JsonNull)
        put("lastUsedAt", lastUsedAt?.let(::JsonPrimitive) ?: JsonNull)
    }
}

internal fun terminalTabsToJson(tabs: List<TerminalTabInfo>): JsonObject = buildJsonObject {
    put("terminals", buildJsonArray { tabs.forEach { add(it.toJson()) } })
    put("total", JsonPrimitive(tabs.size))
}

/**
 * 一种来源（本地 / 某台远程服务器 / 用户手开）的全部 tab。
 *
 * `default` 是**组级**属性而不是每条 tab 上的布尔：一个来源只有一条默认 tab，
 * 把它摊到每条记录上只会让结果里全是 `default: false` 的噪音。
 */
internal data class TerminalSourceGroup(
    val kind: TerminalKind,
    val serverName: String?,
    /** 该来源的默认 tab；null = 这种来源还没用过。 */
    val defaultTab: String?,
    val tabs: List<TerminalTabInfo>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        // 用来源的真实类型，而不是位置描述：`mcp_project_sdk` / `mcp_remote` 和 kind 过滤参数是同一套词。
        put("source", JsonPrimitive(kind.wire))
        // server 只对远程有意义；本地组不给这个键，免得出现"恒为 null"的字段。
        if (kind == TerminalKind.Remote) {
            put("server", JsonPrimitive(serverName.orEmpty()))
        }
        put("default", defaultTab?.let(::JsonPrimitive) ?: JsonNull)
        put("tabs", buildJsonArray { tabs.forEach { add(it.toJson()) } })
    }
}

internal fun terminalGroupsToJson(groups: List<TerminalSourceGroup>): JsonObject = buildJsonObject {
    put("sources", buildJsonArray { groups.forEach { add(it.toJson()) } })
}

/**
 * 面向 agent 的清单：按来源分组，每组先报默认 tab，再列组内 tab 的简表。
 *
 * 用户手开的 tab 只给数量（它们不可控制，逐条列出来只会把 MCP 的 tab 淹掉）；完整记录仍在
 * structuredContent 里。列固定且对齐，`last` 放最后（它最长，不参与对齐）。
 */
internal fun formatTerminalList(groups: List<TerminalSourceGroup>): String {
    if (groups.isEmpty()) return "No terminal tabs found."

    return buildString {
        for ((index, group) in groups.withIndex()) {
            if (index > 0) append('\n')
            append(groupTitle(group))
            append('\n')

            val managed = group.tabs.filter { it.tab != null }
            val userOnly = group.tabs.filter { it.tab == null }
            if (managed.isEmpty() && userOnly.isNotEmpty()) {
                append("  ").append(userOnly.size).append(" tab(s).")
                append('\n')
                continue
            }
            if (managed.isEmpty()) {
                append("  (none)")
                append('\n')
                continue
            }
            append(renderTable(managed))
        }
    }.trimEnd()
}

/** 组的标题行：来源 + 默认 tab（用户组则说明不可寻址）。 */
private fun groupTitle(group: TerminalSourceGroup): String {
    val label = when (group.kind) {
        TerminalKind.ProjectSdk -> "MCP Project SDK"
        TerminalKind.Remote -> "Remote host ${group.serverName.orEmpty()}".trimEnd()
        TerminalKind.User -> "User tabs"
    }
    return when (group.kind) {
        TerminalKind.User -> "$label — opened by the user, not addressable"
        else -> label + " — " + (
            group.defaultTab?.let { "default: $it" }
                ?: "no default yet (the next command creates one)"
            )
    }
}

private fun renderTable(tabs: List<TerminalTabInfo>): String {
    val rows = tabs.map { info ->
        listOf(
            info.tab.orEmpty(),
            info.status,
            info.currentDirectory ?: info.workingDirectory ?: "",
            info.lastCommand?.lineSequence()?.first()?.take(80) ?: "",
        )
    }
    val headers = listOf("tab", "status", "cwd", "last")
    val widths = headers.indices.map { i ->
        maxOf(headers[i].length, rows.maxOfOrNull { it[i].length } ?: 0)
    }
    fun render(cells: List<String>): String = cells
        .mapIndexed { i, cell -> if (i == cells.lastIndex) cell else cell.padEnd(widths[i]) }
        .joinToString("  ")
        .trimEnd()

    return buildString {
        append("  ").append(render(headers)).append('\n')
        rows.forEach { append("  ").append(render(it)).append('\n') }
    }
}

/** 表格里的来源名：本地 / 远程 / 用户手开。 */
/** 一次命令执行的结果。 */
internal data class TerminalRunResult(
    val tab: String,
    val text: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val running: Boolean?,
    val truncated: Boolean,
    /** 命令可能破坏 shell 跟踪时的提示（例如 exec）。 */
    val warning: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tab", JsonPrimitive(tab))
        put("text", JsonPrimitive(text))
        put("exitCode", exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
        put("timedOut", JsonPrimitive(timedOut))
        put("running", running?.let { JsonPrimitive(it) } ?: JsonNull)
        put("truncated", JsonPrimitive(truncated))
        put("warning", warning?.let { JsonPrimitive(it) } ?: JsonNull)
    }
}

/** 一条命令段：命令原文 + 它的输出 + 退出码（远程拿不到时为 null）。 */
internal data class TerminalCommandSegment(
    val command: String,
    val output: String,
    val exitCode: Int?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("command", JsonPrimitive(command))
        put("output", JsonPrimitive(output))
        put("exitCode", exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
    }
}

/** `terminal_read` 的结果。`text` 与 `commands` 二选一。 */
internal data class TerminalReadResult(
    val tab: String,
    val text: String?,
    val commands: List<TerminalCommandSegment>?,
    val running: Boolean?,
    val truncated: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tab", JsonPrimitive(tab))
        if (text != null) put("text", JsonPrimitive(text))
        if (commands != null) {
            put("commands", JsonArray(commands.map { it.toJson() }))
        }
        put("running", running?.let { JsonPrimitive(it) } ?: JsonNull)
        put("truncated", JsonPrimitive(truncated))
    }
}

// ---------------------------------------------------------------------------
// 文本清洗 / 截断
// ---------------------------------------------------------------------------

/** 默认返回的最大字符数。约 7.5k tokens，足够放下构建/测试日志的尾部。 */
internal const val TERMINAL_DEFAULT_MAX_CHARS: Int = 30_000

/** 单次返回的硬上限；agent 可以显式传 maxChars 提高。 */
internal const val TERMINAL_HARD_MAX_CHARS: Int = 200_000

/**
 * 清洗终端文本：
 * - 每行去掉行尾空格（终端会把行补到整屏宽度）；
 * - 去掉末尾连续空行（命令结束后到屏幕底部的空白）；
 * - 超过 [maxChars] 截断并标记。
 */
internal fun cleanTerminalText(
    raw: String,
    maxChars: Int = TERMINAL_DEFAULT_MAX_CHARS,
): Pair<String, Boolean> = capText(normalizeTerminalSnapshot(raw), maxChars)

/** 归一化终端快照：去行尾空格、去尾部空行。用于文本 diff，避免终端补位导致前缀对不上。 */
internal fun normalizeTerminalSnapshot(raw: String): String = raw.lineSequence()
    .map { it.trimEnd() }
    .toList()
    .dropLastWhile { it.isEmpty() }
    .joinToString("\n")

/**
 * 截断到 [maxChars]，返回 (文本, 是否被截断)。
 * 保留**尾部**：终端输出里最有价值的是最新的错误/日志，不是最前面的旧行。
 */
internal fun capText(text: String, maxChars: Int = TERMINAL_DEFAULT_MAX_CHARS): Pair<String, Boolean> =
    if (text.length <= maxChars) {
        text to false
    } else {
        "… (truncated)\n" + tailKeepingLines(text, maxChars) to true
    }

/** 取尾部；如果切点落在行中间，丢掉第一行残片，避免输出以半行数字开头。 */
private fun tailKeepingLines(text: String, maxChars: Int): String {
    val start = (text.length - maxChars).coerceAtLeast(0)
    val tail = text.substring(start)
    return if (start > 0 && text[start - 1] != '\n' && tail.contains('\n')) {
        tail.substringAfter('\n')
    } else {
        tail
    }
}

/**
 * 从最新往旧保留命令段，总输出不超过 [maxChars]。
 * 超出时保留最近的输出；最旧的那条如果只放得下一部分，只保留它的尾部。
 */
internal fun capSegments(
    segments: List<TerminalCommandSegment>,
    maxChars: Int = TERMINAL_DEFAULT_MAX_CHARS,
): Pair<List<TerminalCommandSegment>, Boolean> {
    if (segments.isEmpty()) return emptyList<TerminalCommandSegment>() to false
    if (segments.sumOf { it.output.length } <= maxChars) return segments to false

    val result = ArrayDeque<TerminalCommandSegment>()
    var remaining = maxChars
    var truncated = false
    for (segment in segments.asReversed()) {
        if (remaining <= 0) {
            truncated = true
            break
        }
        if (segment.output.length <= remaining) {
            result.addFirst(segment)
            remaining -= segment.output.length
        } else {
            result.addFirst(
                segment.copy(output = "… (truncated)\n" + tailKeepingLines(segment.output, remaining)),
            )
            truncated = true
            break
        }
    }
    return result.toList() to truncated
}

// ---------------------------------------------------------------------------
// 远程命令段：marker + 文本 diff
// ---------------------------------------------------------------------------

/**
 * 工具发送命令时留下的快照：
 * - [command] / [sentText] 用于把终端里的包装命令（如 `zsh -lic '...'`）映射回用户命令；
 * - [textBefore] 用于判断终端 buffer 是否被裁剪。
 *
 * 命令段的切分本身按提示符做（见 [buildRemoteCommandSegments]），这样用户手动输入的命令
 * 也能出现在 `tail` 里。
 */
internal data class TerminalCommandMarker(
    /** 用户看到的命令（不含 login shell / cd 包装）。 */
    val command: String,
    /** 实际写进终端的文本，用于剥离命令回显。 */
    val sentText: String,
    /** 发送前的终端全文快照。 */
    val textBefore: String,
)

/**
 * 取 [before] 之后新增的文本。若 [after] 不以 [before] 为前缀（终端 buffer 被裁剪），
 * 返回整个 [after] 并标记 truncated。
 */
internal fun textDelta(before: String, after: String): Pair<String, Boolean> {
    if (before.isEmpty()) return after to false
    if (after.startsWith(before)) return after.substring(before.length) to false
    val common = commonPrefixLength(before, after)
    return after.substring(common) to (common < before.length)
}

private fun commonPrefixLength(a: String, b: String): Int {
    val max = minOf(a.length, b.length)
    var i = 0
    while (i < max && a[i] == b[i]) i++
    return i
}

/** 一行「提示符 + 命令」的解析结果。 */
internal data class PromptCommand(
    /** 提示符部分，例如 `yutao@host:~`。 */
    val prompt: String,
    /** 提示符符号：`$` / `#` / `%`。 */
    val marker: Char,
    /** 提示符后面的命令。 */
    val command: String,
)

/**
 * 常见提示符形状。必须**整行同时匹配提示符和命令**，不能只在行里找 `$ `：
 * - `user@host:path$ command`
 * - `user@host path% command`
 * - `[user@host path]$ command`
 * - 裸提示符 `$ command`
 *
 * 这样输出里的 `price: $ 10` 不会被当成命令；命令自身包含 `$ ` 时也由 `(.*)` 整体捕获。
 */
private val PROMPT_PATTERNS: List<Regex> = listOf(
    // user@host:path$ command（可带 (venv) 前缀；path 允许空格）
    Regex("""^((?:\([^)]*\)\s*)?[^\s@]+@[^\s:]+:[^${'$'}#%]*)([${'$'}#%]) (.*)${'$'}"""),
    // user@host path% command
    Regex("""^((?:\([^)]*\)\s*)?[^\s@]+@[^\s:]+(?:\s+[^${'$'}#%]*)?)([${'$'}#%]) (.*)${'$'}"""),
    // [user@host path]$ command
    Regex("""^((?:\([^)]*\)\s*)?\[[^\]]+\])([${'$'}#%]) (.*)${'$'}"""),
    // 裸提示符 "$ command"
    Regex("""^()([${'$'}#%]) (.*)${'$'}"""),
)

/** 解析「提示符 + 命令」；不匹配时返回 null。 */
internal fun matchPromptCommand(line: String): PromptCommand? {
    val trimmed = line.trimEnd()
    if (trimmed.isEmpty()) return null
    for (pattern in PROMPT_PATTERNS) {
        val match = pattern.matchEntire(trimmed) ?: continue
        val marker = match.groupValues[2].firstOrNull() ?: continue
        val command = match.groupValues[3].trim()
        if (command.isEmpty()) continue
        return PromptCommand(
            prompt = match.groupValues[1],
            marker = marker,
            command = command,
        )
    }
    return null
}

private val CONTINUATION_PROMPT = Regex("""^([A-Za-z]*>)\s?(.*)$""")

/** 续行提示符（`> ` / `for> ` / `heredoc> `）后面的命令内容。 */
internal fun extractContinuationCommand(line: String): String? {
    val match = CONTINUATION_PROMPT.matchEntire(line.trimEnd()) ?: return null
    return match.groupValues[2].trim().takeIf { it.isNotEmpty() }
}

/**
 * 从终端全文里按提示符切出命令段。
 *
 * 这样**用户手动输入的命令**也会出现在 `tail` 里，而不只是本工具发送的命令；
 * [markers] 只用来把 `zsh -lic '...'` 这类包装命令映射回用户命令，以及判断 buffer 是否被裁剪。
 */
internal fun buildRemoteCommandSegments(
    text: String,
    markers: List<TerminalCommandMarker>,
    tail: Int?,
    maxChars: Int = TERMINAL_DEFAULT_MAX_CHARS,
): Pair<List<TerminalCommandSegment>, Boolean> {
    val lines = text.lines()
    val starts = lines.mapIndexedNotNull { index, line ->
        matchPromptCommand(line)?.let { index to it.command }
    }
    val truncated = markers.firstOrNull()?.textBefore?.let { it.isNotEmpty() && !text.startsWith(it) } ?: false

    if (starts.isEmpty()) {
        // 超大输出把命令回显挤出 scrollback：用最后一个 marker 合成一条，
        // 输出保留当前 buffer 的尾部；truncated=true 表示历史已经不完整。
        val last = markers.lastOrNull() ?: return emptyList<TerminalCommandSegment>() to truncated
        val outputLines = lines.dropLastWhile { it.isBlank() || looksLikePrompt(it) }
        // reset / clear 后只剩提示符：不要合成「最后一条旧命令」误导用户
        if (outputLines.isEmpty()) return emptyList<TerminalCommandSegment>() to true
        val (output, _) = cleanTerminalText(outputLines.joinToString("\n"), maxChars)
        return listOf(TerminalCommandSegment(last.command, output, null)) to true
    }

    val userCommandBySent = markers.associate { it.sentText.trim() to it.command }
    val all = starts.mapIndexed { i, (lineIndex, rawCommand) ->
        val end = starts.getOrNull(i + 1)?.first ?: lines.size
        val commandBuilder = StringBuilder(rawCommand)
        var outputStart = lineIndex + 1
        while (outputStart < end) {
            val continuation = extractContinuationCommand(lines[outputStart]) ?: break
            commandBuilder.append('\n').append(continuation)
            outputStart++
        }
        val outputLines = lines.subList(outputStart, end)
            .dropLastWhile { it.isBlank() || looksLikePrompt(it) }
        val (output, _) = cleanTerminalText(outputLines.joinToString("\n"), maxChars)
        TerminalCommandSegment(
            command = userCommandBySent[rawCommand] ?: commandBuilder.toString(),
            output = output,
            exitCode = null,
        )
    }
    val selected = if (tail == null || tail >= all.size) all else all.takeLast(tail)
    val (capped, capTruncated) = capSegments(selected, maxChars)
    return capped to (truncated || capTruncated)
}

/**
 * 剥掉命令回显和尾部提示符：
 * - 第一段里包含命令回显（`$ <command>`），去掉到命令第一次出现的那一行；
 * - 末尾如果是一条像提示符的行（以 `$ ` / `# ` 结尾），也去掉。
 */
internal fun stripCommandEchoAndPrompt(
    delta: String,
    command: String,
    maxChars: Int = TERMINAL_DEFAULT_MAX_CHARS,
): Pair<String, Boolean> {
    val lines = delta.lineSequence().map { it.trimEnd() }.toMutableList()
    // 去掉命令回显：找到第一条包含命令文本的行，连同它前面的行一起丢掉
    val echoIndex = lines.indexOfFirst { it.contains(command) }
    if (echoIndex >= 0) {
        repeat(echoIndex + 1) { if (lines.isNotEmpty()) lines.removeAt(0) }
    }
    // 去掉尾部提示符（best-effort）
    while (lines.isNotEmpty() && looksLikePrompt(lines.last())) lines.removeAt(lines.size - 1)
    val cleaned = lines.dropLastWhile { it.isEmpty() }.joinToString("\n")
    return cleanTerminalText(cleaned, maxChars)
}

/** exec 会替换当前 shell，打断 shell integration / 命令跟踪。 */
internal fun shellChangeWarning(command: String): String? {
    val pattern = Regex("""(^|[;&|]\s*)exec(\s|$)""")
    return if (pattern.containsMatchIn(command)) {
        "检测到 exec：会替换当前 shell，shell integration/命令跟踪可能失效；后续命令请新开 tab。"
    } else {
        null
    }
}

internal fun looksLikePrompt(line: String): Boolean {
    val trimmed = line.trimEnd()
    if (trimmed.isEmpty()) return false
    // 常见提示符：以 $ / # / % 结尾（提示符后面的空格已经被 trimEnd 去掉）
    return trimmed.endsWith("$") || trimmed.endsWith("#") || trimmed.endsWith("%")
}

// ---------------------------------------------------------------------------
// 工作目录 / 远程命令包装
// ---------------------------------------------------------------------------

/** 判断本地终端启动命令是否是 Windows CMD（cmd / cmd.exe）。 */
internal fun isCmdShell(shellCommand: List<String>): Boolean {
    val executable = shellCommand.firstOrNull()
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.lowercase()
    return executable == "cmd" || executable == "cmd.exe"
}

/** 把 `~` / `~/...` 展开成给定 home；其它路径原样返回。 */
internal fun expandHomeDirectory(
    path: String?,
    home: String,
    separator: Char = '/',
): String? = when {
    path.isNullOrBlank() -> null
    path == "~" -> home
    path.startsWith("~/") -> {
        val rest = path.removePrefix("~/").replace('/', separator)
        home.trimEnd('/', '\\') + separator + rest
    }
    else -> path
}

/** POSIX 单引号引用；内部单引号用 `'\''` 转义。 */
internal fun posixQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * 构造远程命令：可选的 cd 包装 + 登录 shell 包装。
 *
 * `~` / `~/...` 必须特殊处理：`cd '~'` 不会展开，会报 no such file or directory。
 */
internal fun buildRemoteCommand(
    command: String,
    workingDirectory: String?,
    shell: String,
    loginShell: Boolean,
): String {
    // 不用 `exec`：loginShell 为 false 时这条命令直接跑在 tab 的交互 shell 里，
    // `exec` 会用命令替换掉那个 shell，命令一结束 tab 就没了 —— 也就无法复用。
    val withDir = if (workingDirectory.isNullOrBlank()) {
        command
    } else {
        "${remoteCd(workingDirectory)} && $command"
    }
    if (!loginShell) return withDir
    val shellCommand = when (shell) {
        "zsh" -> "zsh -lic"
        "bash" -> "bash -lc"
        "fish" -> "fish -lc"
        else -> "sh -lc"
    }
    return "$shellCommand ${posixQuote(withDir)}"
}

/**
 * 已存在 tab 里跑的命令：只切目录，不再套登录 shell。
 *
 * 复用时会话本身就是登录 shell，再套一层 `zsh -lic` 只会让 `cd`、`export`
 * 和后台进程都留在子 shell 里，下一条命令看不见。
 */
internal fun buildRemoteCommandInTab(command: String, workingDirectory: String?): String =
    if (workingDirectory.isNullOrBlank()) command else "${remoteCd(workingDirectory)} && $command"

/** `~` / `~/...` 必须特殊处理：`cd '~'` 不会展开，会报 no such file or directory。 */
private fun remoteCd(workingDirectory: String): String = when {
    workingDirectory == "~" -> "cd \"\$HOME\""
    workingDirectory.startsWith("~/") ->
        "cd \"\$HOME\"/${posixQuote(workingDirectory.removePrefix("~/"))}"
    else -> "cd ${posixQuote(workingDirectory)}"
}

/**
 * 远程 tab 名前缀：`mcp-<服务器名>`，实际 tab 名形如 `mcp-mbpmiddleware-1`。
 *
 * 用服务器名而不是 `MCP-R`：用户一眼能看出这条终端连的是哪台机器。
 */
internal fun remoteTabPrefix(serverName: String): String {
    val cleaned = serverName.trim().replace(Regex("""[\s/\\]+"""), "-").ifEmpty { "remote" }
    return "mcp-$cleaned"
}

/**
 * 通用退避序列：250ms、500ms，之后每次 1000ms。
 *
 * 两处用途：① tab 忙等待（绝大多数"忙"是假的 —— shell integration 的 `outputStatus` 在命令
 * 结束或被 Ctrl-C 打断后约 200ms 才翻状态，第一次等待就能过）；② 等进程退出 / 启动 /
 * 配置注册这类轮询。固定 50ms 轮询在 300 秒窗口里会白跑几千次，退避既快过首轮也不浪费。
 */
internal fun retryBackoffMs(attempt: Int): Long = when (attempt) {
    0 -> 250L
    1 -> 500L
    else -> 1_000L
}

/**
 * `terminal_interrupt` 唯一会写的字节：Ctrl-C（ETX）。
 *
 * 只发这一个字符而不是任意文本，是为了让"打断"停留在打断 —— 不留下任何能执行命令的通道。
 */
internal const val TERMINAL_INTERRUPT_CHAR = "\u0003"
