package com.shenyuanlaolarou.ideamcpenhance.prompt.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.shenyuanlaolarou.ideamcpenhance.prompt.format.formatDiagnostics

/**
 * 把**当前文件**的全部 ERROR / WARNING 诊断复制为 prompt：`location:<路径>` + 诊断列表（行号 + 消息 + 出错行）。
 *
 * 范围是整个文件（不是选区）：诊断天然是文件级信息，按选区裁剪会让用户困惑"为什么少了几条"。
 * 输出格式契约见 `SPEC.md §2`（改动必须同步 `PromptFormatTest`）。
 */
class CopyDiagnosticsAction : AnAction() {

    /** 见 [CopyAsPromptAction.getActionUpdateThread] 的说明。 */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val filePath = promptFilePath(project, psiFile) ?: return

        val diagnostics = collectDiagnostics(project, editor.document)
        if (diagnostics.isEmpty()) return

        copyToClipboard(formatDiagnostics(filePath, diagnostics))
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = project != null &&
                editor != null &&
                hasDiagnostics(project, editor.document)
    }
}