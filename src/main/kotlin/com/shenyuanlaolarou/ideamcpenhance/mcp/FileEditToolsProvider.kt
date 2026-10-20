package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/**
 * `edit_file` 工具：把写文件这件事搬进 IDE，让 IDE 的内存副本与磁盘不再是两份东西。
 *
 * ## 解决的问题
 *
 * agent 用进程外的 `write` / `edit` 写文件时，IDE 对**编辑器里打开过的文件**仍持有一份旧副本。
 * 在 IDE 重读磁盘之前，`read_file` / `lint_files` 读到的是旧内容，`reformat_file` 更会
 * 把旧副本格式化后**存回磁盘** —— agent 刚写的内容被整个冲掉，而且没有任何报错。
 *
 * 走这个工具，写的是 IDE 那一份：文件开着就改 `Document`（用户编辑器里立刻看到），
 * 没开着就写 `VirtualFile`（VFS 当场知道，不用等刷新）。磁盘与内存从此一致。
 *
 * ## 失败一律说人话
 *
 * MCP Server 只在 `isError == false` 时才把结构化内容送上线（见 AGENTS.md 的注意事项），
 * 所以**错误的关键信息必须写在 text 里**，`structuredContent` 只在成功时给。
 */
class FileEditToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(EditFileTool())
}

private class EditFileTool : McpTool {

    override val descriptor: McpToolDescriptor = editFileDescriptor

    override suspend fun call(args: JsonObject): McpToolCallResult = try {
        body(args)
    } catch (e: Throwable) {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
        McpToolCallResult.error("Tool ${descriptor.name} failed: $detail", null)
    }

    private suspend fun body(args: JsonObject): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error(
                "$EDIT_FILE_TOOL_NAME: no project is bound to this MCP session.",
                null,
            )

        val filePath = args.requiredString("filePath")
            ?: return McpToolCallResult.error(
                "Missing required argument `filePath`.",
                buildJsonObject { put("argument", "filePath") },
            )

        // oldText / newText **允许空串**：oldText 为空是"新建文件"这一支的语义，
        // 所以不能用 requiredString（它把空串当缺失）。
        val oldText = args.rawString("oldText")
            ?: return McpToolCallResult.error(
                "Missing required argument `oldText` (use an empty string to create a new file).",
                buildJsonObject { put("argument", "oldText") },
            )
        val newText = args.rawString("newText")
            ?: return McpToolCallResult.error(
                "Missing required argument `newText`.",
                buildJsonObject { put("argument", "newText") },
            )
        val replaceAll = args.optionalBoolean("replaceAll", false)

        val absolutePath = FileEditAccess.resolve(project, filePath)

        val outcome = try {
            FileEditAccess.edit(project, absolutePath, oldText, newText, replaceAll)
        } catch (failure: FileEditAccess.EditFailure) {
            return McpToolCallResult.error(failure.agentMessage(), null)
        }

        return McpToolCallResult.text(
            if (outcome.created) {
                "Created ${outcome.path} (${outcome.byteCount} bytes, ${outcome.lineCount} lines)."
            } else {
                "Replaced ${outcome.replacements} occurrence(s) in ${outcome.path} " +
                    "(${outcome.byteCount} bytes, ${outcome.lineCount} lines)."
            },
            buildJsonObject {
                put("path", outcome.path)
                put("created", outcome.created)
                put("replacements", outcome.replacements)
                put("writtenThrough", outcome.writtenThrough)
                put("lineCount", outcome.lineCount)
                put("byteCount", outcome.byteCount)
            },
        )
    }
}

/**
 * 取一个**允许空串**的字符串。
 *
 * `optionalString`（Run Lifecycle 那套）把空串当"没传"，而这里空串是有意义的：
 * `oldText = ""` 表示"新建文件"。
 */
private fun JsonObject.rawString(key: String): String? {
    val element = this[key] ?: return null
    if (element is kotlinx.serialization.json.JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content
}
