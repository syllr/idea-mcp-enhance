package com.shenyuanlaolarou.ideamcpenhance.prompt.format

/**
 * prompt 输出格式的**唯一实现处**：全部是纯函数，无平台依赖、无 IO、不读环境，
 * 因此可以在 `./gradlew test` 里直接断言（见 `PromptFormatTest`）。
 *
 * 两个 action 的输出遵循同一套"信封"约定：
 *
 * - 首行 `location:<路径>`；带选区时追加 `:<行号或起止行号>`
 * - 中间是内容（代码围栏 / 诊断列表）
 * - 结尾固定追加 [TRAILING_BLANK]
 *
 * 本文件与上游 `syllr/copy-as-prompt` 保持逐字节一致：它是一份对外契约，
 * 改动会让所有历史 prompt 的格式失去一致性。
 */

/**
 * 结尾固定追加的「换行 + 两个空格」。
 *
 * 目标输入框（LLM 对话界面 / contenteditable）只渲染"有内容的行"：纯 `\n` 结尾不会产生可见空行，
 * 用户粘贴后光标紧贴内容末尾，继续输入会被吞进代码块。两个空格让末行"有内容"从而渲染出可见空行，
 * 同时视觉上不可见。**不要改成单个换行。**
 */
internal const val TRAILING_BLANK = "\n  "

/** 诊断的严重级别。只保留 IDE 以红色/黄色波浪线标出的两档。 */
enum class DiagnosticSeverity { ERROR, WARNING }

/**
 * 一条诊断（IDE 高亮 / inspection 结果）的形态无关表示。
 *
 * @param line 1-based 行号，与编辑器状态栏显示一致
 * @param message 单行纯文本消息（HTML 在采集阶段已剥离）
 * @param codeLine 出错行原文（已 trim）；该行无可见文本时为 null
 */
data class Diagnostic(
    val severity: DiagnosticSeverity,
    val line: Int,
    val message: String,
    val codeLine: String?,
)

/**
 * 编辑器选区 → prompt 文本。
 *
 * @param language 代码围栏的语言标识（如 `kotlin`）；为 null / 空白时输出不带语言标识的裸围栏
 */
fun formatAsPrompt(
    filePath: String,
    startLine: Int,
    endLine: Int,
    selectedText: String,
    language: String? = null,
): String {
    val lineRange = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
    val fence = if (language.isNullOrBlank()) "```" else "```${language.lowercase()}"
    return "location:$filePath:$lineRange\ncontent:\n$fence\n$selectedText\n```$TRAILING_BLANK"
}

/**
 * 诊断列表 → prompt 文本。
 *
 * 列表为空时不应生成输出：调用方（action 的 enabled 判定）负责保证至少有一条诊断。
 */
fun formatDiagnostics(filePath: String, diagnostics: List<Diagnostic>): String {
    val lines = mutableListOf("location:$filePath", "diagnostics:")
    for (diagnostic in diagnostics) {
        lines += "- [${diagnostic.severity.name}] line ${diagnostic.line}: ${diagnostic.message}"
        val codeLine = diagnostic.codeLine
        if (!codeLine.isNullOrBlank()) {
            // 两个空格缩进让这一行归属上面的 Markdown 列表项
            lines += "  $codeLine"
        }
    }
    return lines.joinToString("\n") + TRAILING_BLANK
}

/**
 * 稳定排序：行号升序 → 同行 ERROR 在前 → 消息字典序。
 *
 * 平台上同一位置的诊断顺序不稳定，显式排序保证同样的问题总是产生同样的 prompt（便于测试与 diff）。
 */
fun List<Diagnostic>.sortedForPrompt(): List<Diagnostic> =
    sortedWith(compareBy({ it.line }, { it.severity.ordinal }, { it.message }))