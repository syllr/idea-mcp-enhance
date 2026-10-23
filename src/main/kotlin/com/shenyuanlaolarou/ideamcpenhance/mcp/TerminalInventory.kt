package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.util.Collections
import java.util.IdentityHashMap

/** 当前仍存活的终端 tab 标题；新 tab 命名时只避开这些。 */
internal suspend fun aliveTerminalTabNames(project: Project): Set<String> = withContext(Dispatchers.EDT) {
    val names = mutableSetOf<String>()

    runCatching {
        TerminalToolWindowTabsManager.getInstance(project).tabs
    }.getOrDefault(emptyList()).forEach { tab ->
        val running = runCatching { tab.view.sessionState.value }.getOrNull()
        if (running !is TerminalViewSessionState.Running) return@forEach
        runCatching { tab.content.tabName }.getOrNull()?.takeIf { it.isNotBlank() }?.let(names::add)
    }

    val manager = runCatching { TerminalToolWindowManager.getInstance(project) }.getOrNull()
    runCatching { manager?.terminalWidgets }.getOrNull().orEmpty().forEach { widget ->
        if (runCatching { widget.ttyConnector?.isConnected }.getOrNull() != true) return@forEach
        runCatching { manager?.getContainer(widget)?.content?.tabName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(names::add)
    }
    names
}

/**
 * 终端清单：把 MCP 管理的 tab、用户自己打开的 tab 放在同一张表里。
 *
 * 只有 `TerminalSessionRegistry` 里的 tab 有 id，能传给 `terminal_io`；
 * 用户手动打开的 tab 只能展示，不可寻址。
 *
 * 所有 UI 读取都在 EDT 上完成；这里只读，不创建/关闭 tab。
 */
internal suspend fun listTerminalTabs(
    project: Project,
    includeUnmanaged: Boolean,
): List<TerminalTabInfo> = withContext(Dispatchers.EDT) {
    val registry = project.service<TerminalSessionRegistry>()
    val states = registry.snapshot()

    val managedLocalByView = mutableMapOf<TerminalView, Pair<String, TerminalSessionRegistry.State>>()
    val managedByContent = mutableMapOf<Content, Pair<String, TerminalSessionRegistry.State>>()
    val managedRemoteByWidget = mutableMapOf<TerminalWidget, Pair<String, TerminalSessionRegistry.State>>()

    for ((tab, state) in states) {
        when (val handle = state.uiHandle) {
            is LocalTerminalHandle -> {
                managedLocalByView[handle.view] = tab to state
                managedByContent[handle.content] = tab to state
            }
            is TerminalWidget -> {
                managedRemoteByWidget[handle] = tab to state
                remoteContent(project, handle)?.let { managedByContent[it] = tab to state }
            }
        }
    }

    val result = mutableListOf<TerminalTabInfo>()
    val addedTabs = mutableSetOf<String>()
    val seenContents = Collections.newSetFromMap(IdentityHashMap<Content, Boolean>())

    fun addManaged(tab: String, state: TerminalSessionRegistry.State) {
        if (addedTabs.add(tab)) result += managedInfo(project, tab, state)
    }

    val frontendTabs = runCatching {
        TerminalToolWindowTabsManager.getInstance(project).tabs
    }.getOrDefault(emptyList())

    for (tab in frontendTabs) {
        val view = runCatching { tab.view }.getOrNull() ?: continue
        val content = runCatching { tab.content }.getOrNull()
        content?.let { seenContents.add(it) }

        val managed = content?.let { managedByContent[it] } ?: managedLocalByView[view]
        if (managed != null) {
            addManaged(managed.first, managed.second)
        } else if (includeUnmanaged) {
            result += userInfoFromView(tab, view, content)
        }
    }

    val manager = runCatching { TerminalToolWindowManager.getInstance(project) }.getOrNull()
    val legacyWidgets = runCatching { manager?.terminalWidgets }.getOrNull().orEmpty()
    for (widget in legacyWidgets) {
        val content = runCatching { manager?.getContainer(widget)?.content }.getOrNull()
        if (content != null && !seenContents.add(content)) continue

        val managed = managedRemoteByWidget[widget]
        if (managed != null) {
            addManaged(managed.first, managed.second)
        } else if (includeUnmanaged) {
            result += userInfoFromWidget(widget, content)
        }
    }

    // 平台枚举不到（例如远程 legacy tab）的受管 tab，仍以 registry 为准列出来。
    for ((tab, state) in states) addManaged(tab, state)
    result
}

private fun managedInfo(
    project: Project,
    tab: String,
    state: TerminalSessionRegistry.State,
): TerminalTabInfo {
    val local = state.uiHandle as? LocalTerminalHandle
    val remote = state.uiHandle as? TerminalWidget

    val alive = when {
        local != null -> !Disposer.isDisposed(local.content) && local.content.isValid
        remote != null -> !Disposer.isDisposed(remote) && remote.ttyConnector?.isConnected == true
        else -> false
    }
    val status = when {
        !alive -> "closed"
        state.lastRunning == true -> "running"
        state.lastRunning == false -> "idle"
        local != null -> viewStatus(local.view)
        remote != null -> {
            val screen = runCatching { remote.getText().toString() }.getOrNull()
            if (screen?.lines()?.lastOrNull { it.isNotBlank() }?.let(::looksLikePrompt) == true) {
                "idle"
            } else {
                "unknown"
            }
        }
        else -> "unknown"
    }
    val title = local?.content?.tabName?.takeIf { it.isNotBlank() }
        ?: remote?.let { remoteContent(project, it)?.tabName }?.takeIf { !it.isNullOrBlank() }
        ?: tab
    val currentDirectory = local?.let { runCatching { it.view.workingDirectoryFlow.value?.toString() }.getOrNull() }
        ?: remote?.let { runCatching { it.getCurrentDirectory() }.getOrNull() }

    return TerminalTabInfo(
        tab = tab,
        kind = state.kind,
        addressable = alive,
        title = title,
        status = status,
        shell = state.shell,
        workingDirectory = state.workingDirectory,
        currentDirectory = currentDirectory,
        serverName = state.serverName,
        lastCommand = state.lastCommand,
        lastCommandAt = state.lastCommandAt,
        lastExitCode = state.lastExitCode,
        lastTimedOut = state.lastTimedOut,
        createdAt = state.createdAt,
        lastUsedAt = state.lastUsedAt,
    )
}

private fun userInfoFromView(
    tab: TerminalToolWindowTab,
    view: TerminalView,
    content: Content?,
): TerminalTabInfo {
    val processOptions = runCatching { tab.processOptions }.getOrNull()
    val shell = processOptions?.shellCommand?.firstOrNull()
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
    return TerminalTabInfo(
        tab = null,
        kind = TerminalKind.User,
        addressable = false,
        title = titleOf(content, view),
        status = viewStatus(view),
        shell = shell,
        workingDirectory = processOptions?.workingDirectory,
        currentDirectory = runCatching { view.workingDirectoryFlow.value?.toString() }.getOrNull(),
        serverName = null,
        lastCommand = null,
        lastCommandAt = null,
        lastExitCode = null,
        lastTimedOut = null,
        createdAt = null,
        lastUsedAt = null,
    )
}

private fun userInfoFromWidget(widget: TerminalWidget, content: Content?): TerminalTabInfo {
    val shell = runCatching { widget.shellCommand.orEmpty().firstOrNull() }.getOrNull()
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
    val screen = runCatching { widget.getText().toString() }.getOrNull()
    val idle = screen?.lines()?.lastOrNull { it.isNotBlank() }?.let(::looksLikePrompt) == true
    return TerminalTabInfo(
        tab = null,
        kind = TerminalKind.User,
        addressable = false,
        title = content?.tabName?.takeIf { it.isNotBlank() }
            ?: runCatching { widget.terminalTitle.buildTitle() }.getOrNull(),
        status = if (idle) "idle" else "unknown",
        shell = shell,
        workingDirectory = null,
        currentDirectory = runCatching { widget.getCurrentDirectory() }.getOrNull(),
        serverName = null,
        lastCommand = null,
        lastCommandAt = null,
        lastExitCode = null,
        lastTimedOut = null,
        createdAt = null,
        lastUsedAt = null,
    )
}

private fun titleOf(content: Content?, view: TerminalView): String? =
    content?.tabName?.takeIf { it.isNotBlank() }
        ?: runCatching { view.title.buildTitle() }.getOrNull()

private fun viewStatus(view: TerminalView): String = when (
    runCatching { view.sessionState.value }.getOrNull()
) {
    is TerminalViewSessionState.Running -> "running"
    is TerminalViewSessionState.Terminated -> "terminated"
    is TerminalViewSessionState.NotStarted -> "not_started"
    else -> "unknown"
}

private fun remoteContent(project: Project, widget: TerminalWidget): Content? =
    runCatching { TerminalToolWindowManager.getInstance(project).getContainer(widget)?.content }.getOrNull()
