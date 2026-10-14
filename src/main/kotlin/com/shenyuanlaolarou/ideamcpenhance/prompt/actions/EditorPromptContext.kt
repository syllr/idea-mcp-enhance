package com.shenyuanlaolarou.ideamcpenhance.prompt.actions

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFile
import com.shenyuanlaolarou.ideamcpenhance.prompt.format.Diagnostic
import com.shenyuanlaolarou.ideamcpenhance.prompt.format.DiagnosticSeverity
import com.shenyuanlaolarou.ideamcpenhance.prompt.format.sortedForPrompt
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 平台侧接线层：只负责把 IDE 里的数据取成 `format` 层需要的纯数据，**不做任何格式化**。
 *
 * 格式化规则全部在 `com.shenyuanlaolarou.ideamcpenhance.prompt.format.PromptFormat` 里，那边是可单测的纯函数。
 */

/** 写入系统剪贴板。两个 action 共用（不引入平台 `CopyPasteManager`，理由见 `DESIGN.md §2.2 决策 4`）。 */
internal fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/**
 * prompt 里使用的文件路径：能算出相对项目根的路径就用相对路径，否则回退为绝对路径。
 *
 * 相对路径更短，且把 prompt 分享给别人 / 贴进 issue 时不会暴露本机目录结构。
 */
internal fun promptFilePath(project: Project, psiFile: PsiFile): String? {
    val virtualFile = psiFile.virtualFile ?: return null
    val basePath = project.basePath
    if (basePath != null) {
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath)
        if (baseDir != null) {
            VfsUtilCore.getRelativePath(virtualFile, baseDir, '/')?.let { return it }
        }
    }
    return virtualFile.path
}

/**
 * 代码围栏的语言标识，取自 PSI 语言 id（平台的 id 多为首字母大写，如 `JAVA`；格式化时会转小写）。
 */
internal fun languageTagOf(psiFile: PsiFile): String? = psiFile.language.id.takeIf { it.isNotBlank() }

/**
 * 是否至少存在一条 ERROR / WARNING 诊断。
 *
 * 供 action 的 `update()` 判定 enabled。命中第一条立即中断遍历，避免菜单渲染时扫完整个文件的全部高亮。
 */
internal fun hasDiagnostics(project: Project, document: Document): Boolean {
    val markup = documentMarkupModel(project, document) ?: return false
    var found = false
    markup.processRangeHighlightersOverlappingWith(0, document.textLength) { highlighter ->
        val info = HighlightInfo.fromRangeHighlighter(highlighter)
        if (info != null && info.severity >= HighlightSeverity.WARNING) {
            found = true
            false // 中断遍历
        } else {
            true
        }
    }
    return found
}

/**
 * 采集当前文档的全部 ERROR / WARNING 诊断，按行号稳定排序。
 *
 * 关键实现约束（改这里之前先读 `DESIGN.md §3`）：
 *
 * 1. daemon（inspection / annotator）把高亮存在 **document 级**的 `DocumentMarkupModel` 里，而**不是**
 *    editor 自己的 markup model。平台自身（`HighlightInfoUpdaterImpl`）也读 document 级模型，这里保持一致。
 * 2. `HighlightInfo.fromRangeHighlighter` 是把 `RangeHighlighter` 还原成诊断信息的公开入口；
 *    它内部读 `getErrorStripeTooltip()`，因此只有 daemon 生成的高亮能被还原，编辑器里其它高亮（搜索命中、
 *    引用高亮等）会被自动排除。
 * 3. platform 的 `DaemonCodeAnalyzerImpl.getHighlights` 带 `@TestOnly`，**生产代码不可用**。
 * 4. processor 在 MarkupModel 锁内执行：只允许"过滤 + 入列"，禁止格式化、IO 或改 markup model。
 */
internal fun collectDiagnostics(project: Project, document: Document): List<Diagnostic> {
    val markup = documentMarkupModel(project, document) ?: return emptyList()

    val infos = mutableListOf<HighlightInfo>()
    markup.processRangeHighlightersOverlappingWith(0, document.textLength) { highlighter ->
        val info = HighlightInfo.fromRangeHighlighter(highlighter)
        if (info != null && info.severity >= HighlightSeverity.WARNING) {
            infos += info
        }
        true
    }

    return infos
        .map { toDiagnostic(document, it) }
        .distinct() // 注入片段(如 Java 里的 SQL 字符串)可能与宿主文件产生重复诊断，去重兜底
        .sortedForPrompt()
}

/**
 * 取 document 级 markup model。
 *
 * `create = false`：只读已存在的高亮容器，不为一个只读操作创建平台对象。
 */
private fun documentMarkupModel(project: Project, document: Document): MarkupModelEx? =
    DocumentMarkupModel.forDocument(document, project, false) as? MarkupModelEx

private fun toDiagnostic(document: Document, info: HighlightInfo): Diagnostic {
    val offset = info.startOffset.coerceIn(0, document.textLength)
    val lineIndex = document.getLineNumber(offset)
    val lineStart = document.getLineStartOffset(lineIndex)
    val lineEnd = document.getLineEndOffset(lineIndex)

    return Diagnostic(
        severity = if (info.severity >= HighlightSeverity.ERROR) {
            DiagnosticSeverity.ERROR
        } else {
            DiagnosticSeverity.WARNING
        },
        line = lineIndex + 1,
        message = plainMessage(info.description),
        codeLine = document.getText(TextRange(lineStart, lineEnd)).trim(),
    )
}

/**
 * 把 inspection 的描述转成单行纯文本。
 *
 * 描述可能带 HTML（例如带 `<code>` 高亮的 inspection 消息）与换行，直接塞进 prompt 会破坏列表结构。
 * `stripHtml(convertBreaks = true)` 把 `<br>` 转成换行，再用空格把各行拼回单行；`&nbsp;` 换成普通空格。
 * 这里刻意不使用正则：只需要字符替换，Regex 会引入不必要的编译与回溯成本。
 */
private fun plainMessage(description: String): String =
    StringUtil.stripHtml(description, true)
        .lineSequence()
        .map { it.replace('\u00A0', ' ').trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")