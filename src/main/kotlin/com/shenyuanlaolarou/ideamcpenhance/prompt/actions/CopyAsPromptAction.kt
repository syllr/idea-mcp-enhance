package com.shenyuanlaolarou.ideamcpenhance.prompt.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.shenyuanlaolarou.ideamcpenhance.prompt.format.formatAsPrompt

/**
 * 把编辑器选区复制为 prompt：`location:<路径>:<行号>` + 代码围栏 + 选中内容。
 *
 * 输出格式契约见 `SPEC.md §1`（改动必须同步 `PromptFormatTest`）。
 */
class CopyAsPromptAction : AnAction() {

    /**
     * 默认即 EDT（本 action 覆写了 `update()`），显式写出以免误判：这里要读选区状态与文档。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel

        val selectedText = selectionModel.selectedText ?: return
        if (selectedText.isBlank()) return

        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val filePath = promptFilePath(project, psiFile) ?: return

        val document = editor.document
        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd
        // 选区恰好结束在某行的行首时（例如从第 10 行选到第 12 行行首），真实覆盖到的是第 11 行末尾，
        // 所以用 endOffset - 1 反查末行，否则会把未选中的那一行算进行号范围。
        val lastSelectedOffset = if (endOffset > startOffset) endOffset - 1 else startOffset

        val startLine = document.getLineNumber(startOffset) + 1
        val endLine = document.getLineNumber(lastSelectedOffset) + 1

        copyToClipboard(
            formatAsPrompt(
                filePath = filePath,
                startLine = startLine,
                endLine = endLine,
                selectedText = selectedText,
                language = languageTagOf(psiFile),
            )
        )
        // 清空选区作为"复制已完成"的视觉反馈
        selectionModel.removeSelection()
    }

    override fun update(e: AnActionEvent) {
        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)
        val selectionModel = editor?.selectionModel
        e.presentation.isEnabled = selectionModel != null &&
                selectionModel.hasSelection() &&
                !selectionModel.selectedText.isNullOrBlank()
    }
}