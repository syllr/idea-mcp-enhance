package com.shenyuanlaolarou.ideamcpenhance.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Before launch 日志尾部预览的契约。
 *
 * 为什么取尾部：一次构建的结论在最末尾（报错 / 最后一行 `done`），头部通常只是命令行回显。
 */
class RunLogTailTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `短日志原样返回`() {
        val file = temp.newFile("a.log").apply { writeText("hello\nworld\n") }
        assertEquals("hello\nworld\n", readRunLogTail(file.absolutePath))
    }

    @Test
    fun `超长日志只取尾部并明确标注被截断`() {
        // 头部的字符不该出现：否则 agent 会以为看到了全文。
        val body = "HEAD_MARKER" + "x".repeat(200) + "TAIL_MARKER"
        val file = temp.newFile("b.log").apply { writeText(body) }

        val preview = readRunLogTail(file.absolutePath, limit = 20)!!

        assertTrue("必须标注被截断", preview.startsWith("<truncated at the start>"))
        assertTrue("尾部内容要在", preview.contains("TAIL_MARKER"))
        assertTrue("头部内容不该在", !preview.contains("HEAD_MARKER"))
    }

    @Test
    fun `空日志返回空串而不是 null`() {
        val file = temp.newFile("c.log").apply { writeText("") }
        assertEquals("", readRunLogTail(file.absolutePath))
    }

    @Test
    fun `文件不存在时返回 null 而不是抛异常`() {
        // 日志被清理是正常降级：不该让工具失败。
        assertNull(readRunLogTail(File(temp.root, "missing.log").absolutePath))
    }

    @Test
    fun `路径非法时返回 null 而不是抛异常`() {
        assertNull(readRunLogTail(""))
    }
}
