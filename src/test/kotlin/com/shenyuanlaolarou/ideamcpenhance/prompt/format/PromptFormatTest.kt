package com.shenyuanlaolarou.ideamcpenhance.prompt.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * prompt 输出格式契约测试。
 *
 * 这些用例是 `SPEC.md §1` / `§2` 的可执行版本：格式变了这里必须一起变，反之亦然。
 */
class PromptFormatTest {

    // ---------- formatAsPrompt：选区 → prompt ----------

    @Test
    fun `单行选区输出单个行号`() {
        assertEquals(
            "location:src/Foo.kt:10\ncontent:\n```kotlin\nval a = 1\n```\n  ",
            formatAsPrompt("src/Foo.kt", 10, 10, "val a = 1", "kotlin")
        )
    }

    @Test
    fun `跨行选区输出起止行号`() {
        assertEquals(
            "location:src/Foo.kt:10-20\ncontent:\n```kotlin\nval a = 1\nval b = 2\n```\n  ",
            formatAsPrompt("src/Foo.kt", 10, 20, "val a = 1\nval b = 2", "kotlin")
        )
    }

    @Test
    fun `语言标识统一转小写`() {
        val actual = formatAsPrompt("Foo.java", 1, 1, "int a;", "JAVA")
        assertEquals("location:Foo.java:1\ncontent:\n```java\nint a;\n```\n  ", actual)
    }

    @Test
    fun `缺失语言标识时输出裸围栏`() {
        val expected = "location:Foo.txt:1\ncontent:\n```\nhello\n```\n  "
        assertEquals(expected, formatAsPrompt("Foo.txt", 1, 1, "hello", null))
        assertEquals(expected, formatAsPrompt("Foo.txt", 1, 1, "hello", ""))
        assertEquals(expected, formatAsPrompt("Foo.txt", 1, 1, "hello", "   "))
    }

    @Test
    fun `选中内容原样保留不做转义`() {
        val code = "```\nval s = \"a\$b\"\n```"
        assertEquals(
            "location:a.kt:3-5\ncontent:\n```kotlin\n$code\n```\n  ",
            formatAsPrompt("a.kt", 3, 5, code, "kotlin")
        )
    }

    @Test
    fun `结尾为换行加两个空格`() {
        assert(formatAsPrompt("a.kt", 1, 1, "x", "kotlin").endsWith("```\n  ")) {
            "结尾必须是结束围栏 + 换行 + 两个空格（见 TRAILING_BLANK 说明）"
        }
    }

    // ---------- formatDiagnostics：诊断 → prompt ----------

    @Test
    fun `诊断列表逐条渲染并缩进出错行`() {
        val diagnostics = listOf(
            Diagnostic(DiagnosticSeverity.ERROR, 12, "Cannot resolve method 'foo'", "foo();"),
            Diagnostic(DiagnosticSeverity.WARNING, 14, "'x' is never used", "val x = 1"),
        )

        assertEquals(
            "location:src/Foo.kt\n" +
                    "diagnostics:\n" +
                    "- [ERROR] line 12: Cannot resolve method 'foo'\n" +
                    "  foo();\n" +
                    "- [WARNING] line 14: 'x' is never used\n" +
                    "  val x = 1\n" +
                    "  ",
            formatDiagnostics("src/Foo.kt", diagnostics)
        )
    }

    @Test
    fun `出错行为空时省略该行`() {
        val diagnostics = listOf(
            Diagnostic(DiagnosticSeverity.ERROR, 7, "Unexpected token", null),
            Diagnostic(DiagnosticSeverity.ERROR, 8, "Only whitespace", "   "),
        )

        assertEquals(
            "location:a.kt\ndiagnostics:\n" +
                    "- [ERROR] line 7: Unexpected token\n" +
                    "- [ERROR] line 8: Only whitespace\n" +
                    "  ",
            formatDiagnostics("a.kt", diagnostics)
        )
    }

    @Test
    fun `诊断格式结尾同样为换行加两个空格`() {
        val diagnostics = listOf(Diagnostic(DiagnosticSeverity.ERROR, 1, "boom", "x"))
        assert(formatDiagnostics("a.kt", diagnostics).endsWith("x\n  ")) {
            "诊断输出也必须以 TRAILING_BLANK 结尾"
        }
    }

    // ---------- sortedForPrompt：稳定排序 ----------

    @Test
    fun `按行号升序且同行 ERROR 在前`() {
        val sorted = listOf(
            Diagnostic(DiagnosticSeverity.ERROR, 5, "b", null),
            Diagnostic(DiagnosticSeverity.WARNING, 1, "a", null),
            Diagnostic(DiagnosticSeverity.WARNING, 5, "a", null),
            Diagnostic(DiagnosticSeverity.ERROR, 1, "c", null),
        ).sortedForPrompt()

        assertEquals(listOf(1, 1, 5, 5), sorted.map { it.line })
        assertEquals(
            listOf(
                DiagnosticSeverity.ERROR,
                DiagnosticSeverity.WARNING,
                DiagnosticSeverity.ERROR,
                DiagnosticSeverity.WARNING,
            ),
            sorted.map { it.severity }
        )
    }

    @Test
    fun `完全相同的诊断排序结果稳定`() {
        val diagnostics = listOf(
            Diagnostic(DiagnosticSeverity.ERROR, 3, "same", "x"),
            Diagnostic(DiagnosticSeverity.ERROR, 3, "same", "x"),
        )
        assertEquals(diagnostics, diagnostics.sortedForPrompt())
    }
}
