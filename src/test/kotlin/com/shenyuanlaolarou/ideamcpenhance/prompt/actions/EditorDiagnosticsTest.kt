package com.shenyuanlaolarou.ideamcpenhance.prompt.actions

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.shenyuanlaolarou.ideamcpenhance.prompt.format.DiagnosticSeverity
import org.junit.Ignore

/**
 * 诊断采集的平台集成测试。
 *
 * 这是本项目里**唯一依赖平台**的测试：它验证 `collectDiagnostics` 真的能从
 * `DocumentMarkupModel`（daemon 存放高亮的地方）里把编译错误捞出来。
 *
 * 之所以必须测平台这一层：`DaemonCodeAnalyzerImpl.getHighlights` 带 `@TestOnly`，生产代码只能走
 * markup model；如果哪天平台改了高亮的存放位置，这个测试会失败，而不是让用户"点了没反应"。
 *
 * 注意：`BasePlatformTestCase` 继承 JUnit 3 的 `TestCase`，因此测试方法必须以 `test` 开头
 * （`@Test` 注解在 JUnit 3 运行器下不生效）。
 */
@Ignore(
    "IntelliJ Platform 2026.2.3 的测试沙箱起不来：打开测试项目时 post-startup 阶段报 " +
        "'Cannot create extension (class=Z.Z.Z.Z.Z) [Plugin: com.intellij.modules.ultimate]' → " +
        "'Cannot find suitable constructor ... expected (), (CoroutineScope), (Application), " +
        "or (Application, CoroutineScope)'。该类来自 product-backend.jar（平台内部混淆产物），" +
        "与本插件无关：把同一份代码搬到上游 syllr/copy-as-prompt 并同样指向 2026.2.3，失败完全一致。" +
        "在 JetBrains 修复前，这个平台集成测试无法在目标平台上运行，因此暂时禁用；" +
        "diagnostics 的实际行为请用 ./gradlew runIde 手工验证。",
)
class EditorDiagnosticsTest : BasePlatformTestCase() {

    fun testCollectsCompileErrorFromMarkupModel() {
        myFixture.configureByText(
            "Foo.java",
            """
            class Foo {
                void m() {
                    undefinedMethod();
                }
            }
            """.trimIndent()
        )
        myFixture.doHighlighting() // 强制跑一次 daemon，把高亮写进 markup model

        val diagnostics = collectDiagnostics(project, myFixture.editor.document)

        assertFalse("应至少采集到一条诊断，实际为空", diagnostics.isEmpty())
        val error = diagnostics.first { it.severity == DiagnosticSeverity.ERROR }
        assertEquals("错误应在第 3 行", 3, error.line)
        assertTrue("消息应包含未解析的方法名，实际: ${error.message}", error.message.contains("undefinedMethod"))
        assertTrue("应带上出错行原文，实际: ${error.codeLine}", error.codeLine?.contains("undefinedMethod") == true)
    }

    fun testHasDiagnosticsTrueForBrokenFile() {
        myFixture.configureByText("Foo.java", "class Foo { void m() { undefinedMethod(); } }")
        myFixture.doHighlighting()

        assertTrue(hasDiagnostics(project, myFixture.editor.document))
    }

    fun testHasDiagnosticsFalseForCleanFile() {
        myFixture.configureByText("Foo.java", "class Foo { void m() { } }")
        myFixture.doHighlighting()

        assertFalse("无问题的文件不应被判定为有诊断", hasDiagnostics(project, myFixture.editor.document))
        assertTrue(collectDiagnostics(project, myFixture.editor.document).isEmpty())
    }

    fun testDiagnosticsAreSortedByLine() {
        myFixture.configureByText(
            "Foo.java",
            """
            class Foo {
                void m() {
                    unknownB();
                    unknownA();
                }
            }
            """.trimIndent()
        )
        myFixture.doHighlighting()

        val lines = collectDiagnostics(project, myFixture.editor.document).map { it.line }
        assertEquals(lines.sorted(), lines)
    }
}
