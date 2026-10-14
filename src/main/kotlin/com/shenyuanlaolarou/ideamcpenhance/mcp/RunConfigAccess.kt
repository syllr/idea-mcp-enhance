package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.KillableProcess
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
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

/** 列出项目里所有 Run Configuration 及其实例状态（含模板——模板永远不在跑，由调用方按 `running` 过滤掉）。 */
internal fun allConfigurationStates(project: Project): List<RunConfigurationState> =
    RunManager.getInstance(project).allSettings.map { snapshotOf(project, it) }

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

/**
 * 发起一次启动，并**等到进程真的起来**为止。
 *
 * 与内置 MCP Server 的 `execute_run_configuration` 对齐：它的文档写的是
 * "When `waitForExit=false`, waits only for the process to start, then returns immediately"。
 * 之前这里直接调 `ExecutionManager.restartRunProfile(environment)`（fire-and-forget）后立刻快照，
 * 必然读到"0 个在跑"，调用方会误以为启动失败——这是必须避免的。
 *
 * 实现上照搬内置 MCP 的做法（`com.intellij.mcpserver.util.Execution_utilKt`）：
 * 用一个 [ProgramRunner.Callback] 接住 `processStarted` / `processNotStarted`，
 * 在 `processStarted` 里给 `ProcessHandler` 挂输出监听并 `startNotify()`，
 * 然后把 stdout/stderr 同时留在内存（内联预览）和落到日志文件（[StartOutcome.fullOutputPath]）。
 *
 * 注意 `ProgramRunnerUtil` 位于 `intellij.platform.execution.impl.jar`，但它的包是
 * `com.intellij.execution`（公开 API），不是被本项目禁止的 `com.intellij.execution.impl.*` 包。
 *
 * @param settings 目标配置。既用来取名字（日志文件名 / sessionId），也用来在启动后取"收敛快照"
 * @param startTimeoutMs 从"发出启动请求"到"等到进程被创建"的上限；超时返回 [StartOutcome.failure]
 * @param outputGraceMs 进程起来后再采集这么久的输出才返回。server 类配置永不退出，只能给它一个固定窗口；
 *   这段时间就是"启动日志"（`output` 内联预览）能拿到多少的代价。日志文件 [StartOutcome.fullOutputPath]
 *   不受这个窗口限制，会在进程整个生命周期里持续增长
 */
internal suspend fun startRunAndAwait(
    project: Project,
    environment: ExecutionEnvironment,
    settings: RunnerAndConfigurationSettings,
    startTimeoutMs: Int,
    outputGraceMs: Long = DEFAULT_OUTPUT_GRACE_MS,
): StartOutcome {
    val sessionName = settings.name
    val startedAtNanos = System.nanoTime()
    val outputPath = createRunOutputFile(sessionName)
    val collector = RunOutputCollector(outputPath)

    val settled = CompletableDeferred<Result<RunContentDescriptor>>()
    val callback = object : ProgramRunner.Callback {
        override fun processStarted(descriptor: RunContentDescriptor) {
            // 官方也是在这里挂监听 + startNotify：descriptor 到手时进程可能已创建但输出还没被订阅
            descriptor.processHandler?.let { handler ->
                handler.addProcessListener(collector.listener)
                handler.startNotify()
            }
            settled.complete(Result.success(descriptor))
        }

        override fun processNotStarted(error: Throwable?) {
            settled.complete(Result.failure(error ?: IllegalStateException("The run was not started.")))
        }
    }

    withContext(Dispatchers.EDT) {
        ProgramRunnerUtil.executeConfigurationAsync(environment, false, true, callback)
    }

    val settledResult = withTimeoutOrNull(startTimeoutMs.toLong().coerceAtLeast(1L)) { settled.await() }
        ?: return failedStart(
            project,
            settings,
            outputPath,
            startedAtNanos,
            "The IDE did not report a started or failed run within ${startTimeoutMs}ms.",
        )

    val descriptor = settledResult.getOrElse { error ->
        return failedStart(
            project,
            settings,
            outputPath,
            startedAtNanos,
            error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName,
        )
    }

    val handler = descriptor.processHandler
    var state = snapshotOf(project, settings)
    var upElapsedMs = elapsedMsSince(startedAtNanos)
    if (handler != null) {
        // 1) 等进程真的被创建出来（pid 可读）或提前退出
        awaitProcessUp(handler, startTimeoutMs)
        // 记下"进程真的起来"的时刻：后面采集日志的固定窗口不该算进这个时间，
        // 否则摘要会写成"启动用了 2 秒"，其实是等日志等了 2 秒
        upElapsedMs = elapsedMsSince(startedAtNanos)
        // 2) 再采一整个窗口的输出。这里**不**在"拿到第一段输出"时就返回：
        //    第一段往往只是 IDE 回显的启动命令行，真正的启动横幅（"listening on ..."）还在后面
        awaitOutputWindow(handler, outputGraceMs)
        // 3) 最后等一个"新实例确实在跑"的快照。这一步不能省，也不能用"executionId 出现"代替——
        //    IDEA 会复用 tab 的 RunContentDescriptor，executionId 先出现时它带的 ProcessHandler
        //    可能还是上一次那个（实测读到过 running=false + 上一次的 pid，而端口已被新进程占着）
        state = awaitLiveState(project, settings, descriptor.executionId, LIVE_STATE_TIMEOUT_MS)
    }

    val live = state.instances.firstOrNull { it.executionId == descriptor.executionId }
    return StartOutcome(
        executionId = descriptor.executionId,
        pid = live?.pid ?: handler?.let(::nativePidOrNull),
        sessionId = buildSessionId(project, sessionName, descriptor),
        output = collector.preview(),
        fullOutputPath = outputPath?.toString(),
        startElapsedMs = upElapsedMs,
        failure = null,
        state = state,
    )
}

/**
 * [startRunAndAwait] 的返回值。`failure` 非空即表示"这次没起来"。
 *
 * @param startElapsedMs 从发出启动请求到**进程真的被创建**的毫秒数。
 *   注意这里刻意**不含**后续采集启动日志的固定窗口——否则摘要会写成
 *   "启动用了 2 秒"，而实际是等日志等了 2 秒
 * @param state 启动收敛之后该配置的真实状态。调用方应直接用它，不要再自己拍一次快照（会读到中间态）
 */
internal data class StartOutcome(
    val executionId: Long?,
    val pid: Long?,
    val sessionId: String?,
    val output: String,
    val fullOutputPath: String?,
    val startElapsedMs: Long,
    val failure: String?,
    val state: RunConfigurationState,
) {
    /** 新进程是否真的起来了。 */
    val started: Boolean get() = failure == null && executionId != null
}

/**
 * 与官方 `buildSessionId` 同语义的会话标识。
 *
 * 同名只有一个活实例时直接用配置名；有重复活实例时用 `名字#executionId` 消歧，
 * 这样 agent 在"同名多开"的场景下也有个稳定句柄可用。
 */
internal fun buildSessionId(project: Project, sessionName: String, descriptor: RunContentDescriptor): String {
    val liveCount = descriptorsByConfigurationName(project)[sessionName]
        .orEmpty()
        .count { it.processHandler?.isProcessTerminated == false }
    return if (liveCount <= 1) sessionName else "$sessionName#${descriptor.executionId}"
}

private fun failedStart(
    project: Project,
    settings: RunnerAndConfigurationSettings,
    outputPath: Path?,
    startedAtNanos: Long,
    reason: String,
): StartOutcome =
    StartOutcome(
        executionId = null,
        pid = null,
        sessionId = null,
        output = "",
        fullOutputPath = outputPath?.toString(),
        startElapsedMs = elapsedMsSince(startedAtNanos),
        failure = reason,
        state = snapshotOf(project, settings),
    )

private fun elapsedMsSince(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000

private fun deadlineNanos(timeoutMs: Long): Long =
    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))

/** 等进程被真正创建（`nativePid` 可读）或提前终止。 */
private suspend fun awaitProcessUp(handler: ProcessHandler, timeoutMs: Int) {
    val deadline = deadlineNanos(timeoutMs.toLong())
    while (System.nanoTime() < deadline) {
        if (handler.isProcessTerminated || nativePidOrNull(handler) != null) return
        delay(START_POLL_INTERVAL_MS)
    }
}

/**
 * 采集输出直到窗口结束（或进程提前退出）。
 *
 * 刻意不做"拿到第一段输出就返回"的优化：第一段输出往往是 runner 回显的启动命令行，
 * 真正有信息量的启动横幅要晚一点；提前返回会让 `output` 看起来"没抓到日志"。
 */
private suspend fun awaitOutputWindow(handler: ProcessHandler, graceMs: Long) {
    if (graceMs <= 0) return
    val deadline = deadlineNanos(graceMs)
    while (System.nanoTime() < deadline) {
        if (handler.isProcessTerminated) return
        delay(START_POLL_INTERVAL_MS)
    }
}

/**
 * 等一个"新实例确实在跑"的快照。
 *
 * **不能**用"`executionId` 出现在列表里"来代替这个判断。IDEA 会复用同一 tab 的
 * `RunContentDescriptor`：新实例的 `executionId` 可能先出现，而那时它携带的 `ProcessHandler`
 * 还是上一次那个。实测撞到过——rerun 返回时 `instances` 是 `executionId=4` 配上一次的 pid、
 * `running=false`，可端口其实已经被新进程占着；3 秒后再查才变成正确的 `(4, <新 pid>, true)`。
 *
 * 所以这里等到 **executionId 命中且 `running=true`** 才收工；超时则返回最后一次快照（宁可给
 * 一个可能偏旧的快照，也不要为了等它把工具调用拖死）。
 */
private suspend fun awaitLiveState(
    project: Project,
    settings: RunnerAndConfigurationSettings,
    executionId: Long,
    timeoutMs: Long,
): RunConfigurationState {
    val deadline = deadlineNanos(timeoutMs)
    var state = snapshotOf(project, settings)
    while (System.nanoTime() < deadline) {
        if (state.instances.any { it.executionId == executionId && it.running }) return state
        delay(START_POLL_INTERVAL_MS)
        state = snapshotOf(project, settings)
    }
    return state
}

/**
 * 收集一次 Run 的 stdout/stderr。
 *
 * 内存里留一份用于内联预览（封顶 [OUTPUT_PREVIEW_LIMIT]，与官方一致），
 * 同时**逐段追加**到日志文件——这样进程还在跑的时候，调用方就能去读完整日志，
 * 语义与官方的 `fullOutputPath` 相同（文件会持续增长，IDE 关闭前一直可用）。
 *
 * 逐段 `APPEND` 写文件很简单但每次都有一次 open/write，对一个长跑服务的日志会略重；
 * 换来的是不需要在任何地方持有并关闭文件句柄（工具返回后进程可能还活着，句柄会泄漏）。
 */
internal class RunOutputCollector(private val outputPath: Path?) {

    private val buffer = StringBuilder()
    private val lock = Any()

    /**
     * 挂到 `ProcessHandler` 上的监听器；只关心文本输出。
     *
     * 直接实现 `ProcessListener` 而不是继承 `ProcessAdapter`：后者在 2026.2 已废弃，
     * 而 `ProcessListener` 的方法现在全都有默认实现，只覆写需要的一个即可。
     */
    val listener: ProcessListener = object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            append(event.text)
        }
    }

    private fun append(chunk: String) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            if (buffer.length < OUTPUT_BUFFER_LIMIT) buffer.append(chunk)
        }
        val path = outputPath ?: return
        runCatching {
            Files.writeString(path, chunk, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /** 内联预览：超出上限时截断并补 `<truncated>`，与官方 `execute_run_configuration` 的 output 一致。 */
    fun preview(): String {
        val text = synchronized(lock) { buffer.toString() }
        return if (text.length <= OUTPUT_PREVIEW_LIMIT) text else text.take(OUTPUT_PREVIEW_LIMIT) + "<truncated>"
    }
}

/**
 * 造一个 Run 输出日志文件，放在 IDE 的 system/tmp 下（与官方 `outputPath` 同目录）。
 *
 * 前缀故意用 `ij_rerun__` 而不是官方的 `ij_run__`：两者的内容与生命周期语义一样，
 * 但放在同一个目录里时能一眼看出哪个是本插件的 rerun 产生的。
 *
 * 任何一步失败都返回 null——拿不到日志文件不该让启动本身失败。
 */
private fun createRunOutputFile(sessionName: String): Path? = runCatching {
    val directory = Path.of(PathManager.getTempPath())
    Files.createDirectories(directory)
    val safeName = sessionName.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
    Files.createFile(directory.resolve("$RUN_OUTPUT_FILE_PREFIX${safeName}_${UUID.randomUUID()}.log"))
}.getOrNull()

/** 轮询间隔：等进程起来、等输出、等 Run 内容注册都用它。 */
private const val START_POLL_INTERVAL_MS = 50L

/** 等"新实例确实在跑"的快照的上限。实测收敛需要 1~3 秒。 */
private const val LIVE_STATE_TIMEOUT_MS = 5_000L

/** 本插件自己产出的 Run 日志文件名前缀。 */
private const val RUN_OUTPUT_FILE_PREFIX = "ij_rerun__"

/** 内存里最多留多少字符的输出（只影响内联预览，日志文件不受这个上限约束）。 */
internal const val OUTPUT_BUFFER_LIMIT = 64 * 1024

/** 内联预览的字符上限，与官方 `execute_run_configuration` 的 10000 对齐。 */
internal const val OUTPUT_PREVIEW_LIMIT = 10_000

/**
 * 进程起来后、返回前，采集输出用的固定窗口。
 *
 * 为什么固定成窗口而不是"等到有输出为止"：第一段输出通常是 runner 回显的启动命令行，
 * 真正的启动横幅（`listening on ...`）要晚一点。实测 1000ms 时 node 只抓到命令行回显，
 * 2000ms 已能覆盖 node / python / java 的启动横幅；更慢的（如 `go run` 首次编译）靠
 * `fullOutputPath` 里那份持续增长的完整日志。
 */
internal const val DEFAULT_OUTPUT_GRACE_MS = 2_000L