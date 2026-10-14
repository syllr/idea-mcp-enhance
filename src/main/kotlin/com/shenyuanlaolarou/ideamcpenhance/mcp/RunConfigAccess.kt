package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.ExecutionManager
import com.intellij.execution.KillableProcess
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit

/**
 * 平台接线层：把 `RunManager` / `RunContentManager` / `ProcessHandler` 拍平成 [RunConfigurationState] 等纯数据，
 * 并执行实际的停止 / 重启动作。
 *
 * **这一层不使用任何 `com.intellij.execution.impl.*` 的内部类。** 内置 MCP Server 缺的"停止"能力，
 * 平台自己的 Stop 按钮是走 `ExecutionManagerImpl.stopProcess` 实现的；这里改为用等价的公开 API
 * 组合实现（`ProcessHandler.TERMINATION_REQUESTED` + `killProcess` / `detachProcess` / `destroyProcess`），
 * 避免把插件绑死在一个 `impl` 包上。语义与 `ExecutionManagerImpl.stopProcess` 逐条对齐，见 [stopHandler]。
 */

/**
 * 按**配置名**把项目当前所有 Run 内容分组。
 *
 * 用 `RunContentDescriptor.getRunConfigurationName()`，而不是 `displayName`：后者是 UI 文本，
 * 多实例时会被加 "(2)" 之类后缀，拿它和配置名比对必然失配——而"同一配置有几个实例"恰恰是本插件要回答的核心问题。
 * 平台自己就是用 `runConfigurationName` 维护这个关联的（见 `setRunConfigurationName`）。
 *
 * 注：2026.1 及更早的 `RunContentDescriptor` 没有 `getRunProfile()`，这里也不依赖它。
 *
 * @return 配置名 → 该配置下的所有 Run 内容（含已结束、只剩标签页的）
 */
internal fun descriptorsByConfigurationName(project: Project): Map<String, List<RunContentDescriptor>> {
    val result = mutableMapOf<String, MutableList<RunContentDescriptor>>()
    for (descriptor in RunContentManager.getInstance(project).allDescriptors) {
        val name = descriptor.runConfigurationName ?: continue
        result.getOrPut(name) { mutableListOf() } += descriptor
    }
    return result
}

/** 全部（非模板的）Run Configuration 名，按名称排序。用于"找不到配置"时给 agent 列候选。 */
internal fun availableConfigurationNames(project: Project): List<String> =
    RunManager.getInstance(project).allSettings
        .filterNot { it.isTemplate }
        .map { it.name }
        .sorted()

/** 按名字找配置：先精确匹配，再忽略大小写。 */
internal fun findSettingsByName(project: Project, configurationName: String): RunnerAndConfigurationSettings? {
    val all = RunManager.getInstance(project).allSettings
    all.firstOrNull { it.name == configurationName }?.let { return it }
    return all.firstOrNull { it.name.equals(configurationName, ignoreCase = true) }
}

/** 该配置名下**仍在跑**（或正在终止）的 Run 内容。 */
internal fun activeDescriptorsFor(project: Project, configurationName: String): List<RunContentDescriptor> =
    descriptorsByConfigurationName(project)[configurationName]
        .orEmpty()
        .filter { it.processHandler?.isProcessTerminated == false }

/** 配置 + 其实例状态 → 纯数据快照。 */
internal fun snapshotOf(project: Project, settings: RunnerAndConfigurationSettings): RunConfigurationState {
    val descriptors = descriptorsByConfigurationName(project)[settings.name].orEmpty()
    return RunConfigurationState(
        name = settings.name,
        typeId = settings.type.id,
        isTemplate = settings.isTemplate,
        uniqueId = settings.uniqueID,
        instances = descriptors.mapNotNull { it.toInstanceState() },
    )
}

/** 列出项目里所有 Run Configuration（可选是否含模板），并附带其实例状态。 */
internal fun listRunConfigurationStates(project: Project, includeTemplates: Boolean): List<RunConfigurationState> =
    RunManager.getInstance(project).allSettings
        .filter { includeTemplates || !it.isTemplate }
        .map { snapshotOf(project, it) }

/**
 * `RunContentDescriptor` → [RunInstanceState]。
 *
 * `processHandler` 为 null 时返回 null：没有 handler 就没有"进程"可言，
 * 硬造一条 `running = true` 的记录只会误导 agent。
 */
internal fun RunContentDescriptor.toInstanceState(): RunInstanceState? {
    val handler = processHandler ?: return null
    return RunInstanceState(
        executionId = executionId,
        displayName = displayName,
        running = !handler.isProcessTerminated,
        terminating = handler.isProcessTerminating,
        startNotified = handler.isStartNotified,
        pid = nativePidOrNull(handler),
        exitCode = handler.exitCode,
    )
}

/**
 * 拿操作系统进程号。
 *
 * `ProcessHandler.getNativePid()` 返回 `CompletableFuture`：进程还没创建出来时 future 尚未完成，
 * 此时 `get()` 会阻塞。这里只取**已经完成且有值**的结果——pid 只是辅助信息，
 * 拿不到不影响工具正确性，不值得为它阻塞一次工具调用。
 */
private fun nativePidOrNull(handler: ProcessHandler): Long? {
    val future = handler.nativePid ?: return null
    if (!future.isDone) return null
    return runCatching { future.getNow(null) }.getOrNull()
}

/**
 * 请求停止一个进程，等价于 `ExecutionManagerImpl.stopProcess`，但只用公开 API。
 *
 * 逐条对齐平台实现：
 * 1. 打上 `TERMINATION_REQUESTED`，让平台知道"用户主动停的"（影响后续是否弹确认等行为）；
 * 2. 已经在终止中且可 kill → 直接 kill（否则会出现"点了停但进程赖着不走"）；
 * 3. 否则按 `detachIsDefault()` 决定走 detach 还是 destroy。
 *
 * 平台实现在 `Dispatchers.Default` 上做第 2/3 步；这里同步执行——`killProcess` / `destroyProcess`
 * 本身都只是非阻塞的信号投递，同步调用不会卡住调用线程。
 */
internal fun stopHandler(handler: ProcessHandler) {
    handler.putUserData(ProcessHandler.TERMINATION_REQUESTED, true)

    if (handler is KillableProcess && handler.isProcessTerminating) {
        handler.killProcess()
        return
    }
    if (handler.isProcessTerminated) return

    if (handler.detachIsDefault()) {
        handler.detachProcess()
    } else {
        handler.destroyProcess()
    }
}

/**
 * 停止一批实例，并在超时前等待它们退出。
 *
 * @param force 超时后是否强杀
 * @return 三元组：实际发起停止的实例数 / 被强杀的实例数 / 等待结束时各实例的最新状态
 */
internal fun stopDescriptors(
    descriptors: List<RunContentDescriptor>,
    waitTimeoutMs: Int,
    force: Boolean,
): StopOutcome {
    val handlers = descriptors.mapNotNull { it.processHandler }
    val requested = handlers.count { !it.isProcessTerminated }
    handlers.forEach(::stopHandler)

    // 留 1ms 下限：waitFor(0) 在部分 handler 上会被当成"无限等待"
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitTimeoutMs.toLong().coerceAtLeast(1L))
    for (handler in handlers) {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) break
        handler.waitFor(TimeUnit.NANOSECONDS.toMillis(remaining))
    }

    val stuck = handlers.filter { !it.isProcessTerminated }
    val forced = if (force && stuck.isNotEmpty()) {
        var killed = 0
        for (handler in stuck) {
            if (handler is KillableProcess && handler.canKillProcess()) {
                handler.killProcess()
                killed++
            }
        }
        // killProcess 只是投递信号，真正退出要等一会儿
        if (killed > 0) stuck.forEach { it.waitFor(FORCE_KILL_WAIT_MS) }
        killed
    } else {
        0
    }

    return StopOutcome(
        requestedStops = requested,
        forcedKills = forced,
        instances = descriptors.mapNotNull { it.toInstanceState() },
    )
}

/** [stopDescriptors] 的返回值。 */
internal data class StopOutcome(
    val requestedStops: Int,
    val forcedKills: Int,
    val instances: List<RunInstanceState>,
) {
    /** 等待窗口结束后仍在跑的实例数。 */
    val stillRunning: Int get() = instances.count { it.running }
}

/** 强杀之后的收尾等待。 */
private const val FORCE_KILL_WAIT_MS = 1_000L

/**
 * 构造一次"Run/Restart"执行环境。
 *
 * 走 `ExecutionEnvironmentBuilder.activeTarget()`，与 IDE 界面上按 Run 时选中的 target 保持一致，
 * 而不是硬编码 `ExecutionTargetManager` 的某个具体实现。
 *
 * 返回 null 表示"IDE 找不到能启动这个配置类型的 runner"，调用方据此报 NO_OP 而不是硬启。
 */
internal fun buildRunEnvironment(project: Project, settings: RunnerAndConfigurationSettings): ExecutionEnvironment? =
    ExecutionEnvironmentBuilder
        .createOrNull(DefaultRunExecutor.getRunExecutorInstance(), settings)
        ?.activeTarget()
        ?.build()

/** 触发"启动 / 重启"，与 IDE 的 Rerun 按钮同一条代码路径。 */
internal fun restartRunProfile(project: Project, environment: ExecutionEnvironment) {
    ExecutionManager.getInstance(project).restartRunProfile(environment)
}