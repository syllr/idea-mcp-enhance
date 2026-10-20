package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `edit_file` 工具的**描述符**（名字、说明、输入 / 输出 JSON Schema、行为注解）。
 *
 * ## 为什么需要这个工具
 *
 * agent 平时用原生 `write` / `edit` 写文件，那是**进程外**的写：内容落到磁盘，IDE 并不知情。
 * 而 IDE 对**在编辑器里打开过的文件**持有一份内存副本（`Document`），它只在 VFS 刷新时才会重读磁盘。
 * 两者不一致的那段时间里，IDE 侧的任何操作（`read_file` / `lint_files` / `reformat_file`）读到的都是**旧副本**；
 * `reformat_file` 更狠 —— 它把旧副本格式化后**存回磁盘**，于是 agent 刚写进去的内容被整个冲掉。
 *
 * 这个工具把写操作搬进 IDE：改的是 `Document`，磁盘由 IDE 自己同步，**内存与磁盘永远是同一份**，
 * 上面那个竞态从根上不存在了。
 *
 * 描述文案是**对外契约**（agent 靠它决定怎么调），与 {@link RunLifecycleSchemas} 里的描述符同规格：
 * 根级 `type: object` + `required` 数组 + 逐字照抄内置的 `projectPath` 描述。
 */
internal val FILE_EDIT_CATEGORY = McpToolCategory(
    "File Edit",
    "com.shenyuanlaolarou.ideamcpenhance.mcp.FileEdit",
    false,
    false,
)

internal const val EDIT_FILE_TOOL_NAME = "edit_file"

internal val editFileDescriptor = McpToolDescriptor(
    EDIT_FILE_TOOL_NAME,
    "Edit File",
    """
    Write to a file **through the IDE**, so the IDE's in-memory copy and the file on disk stay the same
    thing.

    Why this exists: writing a file from outside the IDE leaves the IDE holding a stale in-memory copy of
    it (for files already open in an editor). Until the IDE reloads, every IDE-side tool reads that stale
    copy — and `reformat_file` goes further: it formats the stale copy and **writes it back**, silently
    discarding whatever was just written. Writing through the IDE removes that race entirely.

    How it edits: `oldText` is replaced by `newText`. `oldText` must match the file **verbatim** — it is
    not a regex and not whitespace-normalized. If `oldText` occurs more than once, the call is rejected
    unless `replaceAll` is true, so a half-applied edit can never happen by accident.

    Creating a file: leave `oldText` empty and pass the whole content as `newText`. The parent
    directories are created as needed. An existing file is never overwritten by an empty `oldText` — that
    is rejected, because overwriting by accident is the one mistake worth refusing.

    The edit is applied to the editor's document when the file is open (unsaved editor changes included),
    and to the file itself otherwise. Either way the result is what the IDE will use from now on.
    """.trimIndent(),
    FILE_EDIT_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.edit_file",
    schema(
        properties = properties(
            "projectPath" to projectPathProperty(),
            "filePath" to property(
                "string",
                "Path of the file to edit. Relative paths are resolved against the project root; " +
                    "absolute paths are used as given.",
            ),
            "oldText" to property(
                "string",
                "The text to replace, verbatim. Empty string means \"create the file with newText\" — " +
                    "which is rejected when the file already exists.",
            ),
            "newText" to property("string", "The replacement text."),
            "replaceAll" to property(
                "boolean",
                "Replace every occurrence of oldText instead of requiring it to be unique. " +
                    "Defaults to false.",
            ),
        ),
        required = setOf("filePath", "oldText", "newText"),
    ),
    schema(
        properties = properties(
            "path" to property("string", "Absolute path of the file that was written."),
            "created" to property("boolean", "true when the file did not exist and was created."),
            "replacements" to property("integer", "How many occurrences of oldText were replaced."),
            "writtenThrough" to property(
                "string",
                "\"document\" when the IDE's open document was edited, \"file\" when the file itself was written.",
            ),
            "lineCount" to property("integer", "Line count of the file after the edit."),
            "byteCount" to property("integer", "Size of the file after the edit, in bytes."),
        ),
        required = setOf("path", "created", "replacements", "writtenThrough", "lineCount", "byteCount"),
    ),
    annotations(readOnly = false, destructive = false, idempotent = true),
)

/** 输出 schema 里用到的可空字符串。 */
internal fun nullableString(description: String): JsonObject = buildJsonObject {
    put("type", buildJsonArray {
        add(JsonPrimitive("string"))
        add(JsonPrimitive("null"))
    })
    put("description", description)
}
