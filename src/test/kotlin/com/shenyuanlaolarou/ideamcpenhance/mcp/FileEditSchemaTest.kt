package com.shenyuanlaolarou.ideamcpenhance.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `edit_file` 的契约测试：描述符形状 + 纯函数（出现次数、行号）。
 *
 * 刻意**不测**的部分：真正写文件的那段（要 IDE 的 VFS 与 Document）。
 * 它只能靠 `./gradlew runIde` 手工验证 —— 硬写成一个跑不动的测试只会变成噪音。
 */
class FileEditSchemaTest {

    private fun tools() = FileEditToolsProvider().getTools()

    private fun toolByName(name: String) = tools().first { it.descriptor.name == name }

    @Test
    fun `provider 只暴露 edit_file 一个工具`() {
        assertEquals(listOf(EDIT_FILE_TOOL_NAME), tools().map { it.descriptor.name })
    }

    @Test
    fun `入参契约`() {
        val schema = toolByName(EDIT_FILE_TOOL_NAME).descriptor.inputSchema
        assertEquals(
            setOf("projectPath", "filePath", "oldText", "newText", "replaceAll"),
            schema.propertiesSchema.keys,
        )
        // oldText / newText 必填：oldText 允许空串（那是"新建文件"的语义），但不能缺省。
        assertEquals(
            setOf("filePath", "oldText", "newText"),
            schema.requiredProperties,
        )
    }

    @Test
    fun `输出契约`() {
        val schema = toolByName(EDIT_FILE_TOOL_NAME).descriptor.outputSchema!!
        assertEquals(
            setOf("path", "created", "replacements", "writtenThrough", "lineCount", "byteCount"),
            schema.propertiesSchema.keys,
        )
    }

    @Test
    fun `描述文案要说清为什么要走 IDE`() {
        val description = toolByName(EDIT_FILE_TOOL_NAME).descriptor.description
        assertTrue("要提到旧副本", description.contains("stale"))
        assertTrue("要提到 reformat 会覆盖", description.contains("reformat_file"))
    }

    @Test
    fun `出现次数按不重叠计数`() {
        assertEquals(2, FileEditAccess.countOccurrences("aXbXc", "X"))
        // 不重叠计数："aaaa" 里的 "aa" 是两处，不是三处
        assertEquals(2, FileEditAccess.countOccurrences("aaaa", "aa"))
        assertEquals(0, FileEditAccess.countOccurrences("abc", "z"))
        assertEquals(0, FileEditAccess.countOccurrences("abc", ""))
    }

    @Test
    fun `出现行号从 1 开始`() {
        assertEquals(listOf(1, 3), FileEditAccess.linesOf("foo\nbar\nfoo", "foo"))
        assertEquals(listOf(2), FileEditAccess.linesOf("a\nbb\nc", "bb"))
    }

    @Test
    fun `行数按换行计`() {
        assertEquals(1, FileEditAccess.lineCount(""))
        assertEquals(1, FileEditAccess.lineCount("abc"))
        assertEquals(2, FileEditAccess.lineCount("abc\n"))
    }

    @Test
    fun `每种失败都说清下一步`() {
        val notFound = FileEditAccess.EditFailure.FileNotFound("/tmp/a.md")
        assertTrue(notFound.agentMessage().contains("empty `oldText`"))

        val ambiguous = FileEditAccess.EditFailure.AmbiguousText("/tmp/a.md", 2, listOf(3, 9))
        assertTrue(ambiguous.agentMessage().contains("replaceAll=true"))
        assertTrue(ambiguous.agentMessage().contains("3, 9"))

        val exists = FileEditAccess.EditFailure.AlreadyExists("/tmp/a.md")
        assertTrue(exists.agentMessage().contains("already exists"))
    }
}
