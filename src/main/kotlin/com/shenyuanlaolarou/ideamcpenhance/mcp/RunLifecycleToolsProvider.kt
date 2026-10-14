package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.coroutineContext

/**
 * 把 Run Configuration 的生命周期能力挂到 IDE 内置的 MCP Server 上。
 *
 * ## 为什么用 `McpToolsProvider` 而不是 `McpToolset`
 *
 * `McpToolset` 那条路是**反射式**的：工具方法的参数与返回值靠 Kotlin 反射 + kotlinx-serialization
 * 的编译器插件合成 schema，返回类型必须是带 `@Serializable` 的对象。内置的 `ExecutionToolset` 走的就是那条路。
 *
 * 本插件选 `McpToolsProvider`（同样是 `com.intellij.mcpServer.mcpToolsProvider` 动态扩展点，
 * `McpToolsListProvider.getAllMcpTools()` 会把它与反射那条路的结果合并）：
 *
 * - 不需要引入 kotlinx-serialization 的编译器插件，也就不用在 `plugin.xml` 里多挂依赖；
 * - 输入 schema 与输出 JSON 全部显式手写，可读、可测、可 diff；
 * - prompt 那一半保持"零第三方依赖"，不会被 MCP 的构建配置污染。
 *
 * 代价是参数要自己从 `JsonObject` 里解析（见 `optionalString` / `optionalInt` / `optionalBoolean`），
 * 这部分由 `RunLifecycleSchemaTest` 覆盖。
 *
 * ## 项目从哪来
 *
 * `McpSessionHandler` 在调用任何 `McpTool` 之前，都会把 `McpCallInfo` 装进协程上下文
 * （`withContext(McpCallAdditionalDataElement(...))`），所以 `coroutineContext.projectOrNull`
 * 对**直接实现**的工具同样有效，不需要自己去找"当前打开的项目"。
 *
 * 但有个前提：MCP Server 得先解析出"这次调用针对哪个项目"。它只认两样东西——header
 * `IJ_MCP_SERVER_PROJECT_PATH`，或调用参数里的 `projectPath`；两者都没有时它会在派发到工具之前
 * 直接返回 `Unable to determine the target project`。所以三个工具的 schema 里都声明了
 * `projectPath`（描述与内置工具逐字一致，见 `PROJECT_PATH_DESCRIPTION`）：
 * 配了 header 的客户端不受影响，没配 header 的客户端也有了可用路径。
 *
 * ## 注册方式
 *
 * 由 `META-INF/mcpserver.xml` 注册，该文件只在可选依赖 `com.intellij.mcpServer` 存在时才会被读取
 * （`<depends optional="true" config-file="mcpserver.xml">`）。没有 MCP Server 的 IDE 上，
 * 插件的 prompt 功能照常工作，这些类也不会被加载。
 */
class RunLifecycleToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(
        ListRunConfigurationsTool(),
        StopRunConfigurationTool(),
        RerunRunConfigurationTool(),
    )
}

/**
 * 工具共用的骨架：兜住异常 → 跑具体逻辑。
 *
 * 统一在这里捕获是刻意的：MCP 工具把异常抛出去，agent 拿到的是不可读的失败，而不是结构化错误。
 * 一律转成 `McpToolCallResult.error(...)`，`structuredContent` 里保留同样的信息。
 */
private abstract class BaseRunTool : McpTool {

    // 参数名与父接口保持一致（`McpTool.call(args)`），避免具名实参调用时对不上。
    final override suspend fun call(args: JsonObject): McpToolCallResult = try {
        body(args)
    } catch (e: Throwable) {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
        errorResult("Tool ${descriptor.name} failed: $detail")
    }

    protected abstract suspend fun body(arguments: JsonObject): McpToolCallResult

    protected fun errorResult(message: String, structured: JsonObject? = null): McpToolCallResult =
        McpToolCallResult.error(message, structured)

    /** 取当前 MCP 调用所属的项目。项目被关掉 / 会话没有绑定项目时返回 null。 */
    protected suspend fun currentProject(): Project? = coroutineContext.projectOrNull

    /** `configurationName` 缺失时统一的错误返回。 */
    protected fun missingNameError(): McpToolCallResult =
        errorResult(
            "Missing required argument `configurationName`.",
            buildJsonObject { put("argument", "configurationName") },
        )

    /**
     * 找不到配置时的错误结果，附带候选名。
     *
     * 把候选名一并返回是刻意的：agent 拿到"没找到"却不知道有哪些候选，就只能反复瞎猜配置名；
     * 给出候选能把重试收敛到一次。
     */
    protected fun unknownConfiguration(project: Project, requestedName: String): McpToolCallResult {
        val report = UnknownConfigurationReport(requestedName, availableConfigurationNames(project))
        return McpToolCallResult.error(report.message, report.toJson())
    }

    protected fun noProjectResult(toolName: String): McpToolCallResult =
        McpToolCallResult.error("$toolName: no project is bound to this MCP session.", null)
}

// ---------------------------------------------------------------------------
// list_running_configurations
// ---------------------------------------------------------------------------

private class ListRunConfigurationsTool : BaseRunTool() {

    override val descriptor: McpToolDescriptor = listRunConfigurationsDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return noProjectResult(LIST_TOOL_NAME)
        // 没有模板开关：模板不是实例、永远不会在跑，"只返回在跑的"天然把它排除掉。
        // 也顺手把每个配置的 instances[] 收成只剩活实例，于是整份返回里不含任何"不在跑"的东西。
        val running = allConfigurationStates(project)
            .filter { it.running }
            .map { it.onlyRunningInstances() }

        return McpToolCallResult.text(
            if (running.isEmpty()) {
                "Nothing is running in this project."
            } else {
                "Found ${running.size} running run configuration(s)."
            },
            buildJsonObject {
                put("runningCount", running.size)
                putJsonArray("configurations") {
                    for (state in running) {
                        add(state.toJson())
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// stop_run_configuration
// ---------------------------------------------------------------------------

private class StopRunConfigurationTool : BaseRunTool() {

    override val descriptor: McpToolDescriptor = stopRunConfigurationDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return noProjectResult(STOP_TOOL_NAME)
        val name = arguments.requiredString("configurationName") ?: return missingNameError()

        val settings = findSettingsByName(project, name) ?: return unknownConfiguration(project, name)

        val descriptors = activeDescriptorsFor(project, settings.name)
        if (descriptors.isEmpty()) {
            val idle = StopReport(
                configurationName = settings.name,
                matchedInstances = 0,
                requestedStops = 0,
                forcedKills = 0,
                timedOut = false,
                instances = emptyList(),
                warnings = emptyList(),
            )
            return McpToolCallResult.text("\"${settings.name}\" is not running; nothing to stop.", idle.toJson())
        }

        val waitTimeoutMs = arguments.optionalInt("waitTimeoutMs", defaultWaitTimeoutMs())
        val force = arguments.optionalBoolean("force", true)
        val outcome = stopDescriptors(descriptors, waitTimeoutMs, force)

        val warnings = buildList {
            if (outcome.stillRunning > 0) {
                add("${outcome.stillRunning} instance(s) still running after ${waitTimeoutMs}ms.")
            }
            if (outcome.instances.any { it.stuckTerminating }) {
                add("Some instances are stuck in TERMINATING; the IDE may still show them as running.")
            }
        }

        val report = StopReport(
            configurationName = settings.name,
            matchedInstances = descriptors.size,
            requestedStops = outcome.requestedStops,
            forcedKills = outcome.forcedKills,
            timedOut = outcome.stillRunning > 0,
            instances = outcome.instances,
            warnings = warnings,
        )
        return McpToolCallResult.text(
            "Requested ${outcome.requestedStops} stop(s) on \"${settings.name}\"; " +
                if (report.timedOut) "${outcome.stillRunning} still running after ${waitTimeoutMs}ms."
                else "all instances terminated.",
            report.toJson(),
        )
    }
}

// ---------------------------------------------------------------------------
// rerun_run_configuration
// ---------------------------------------------------------------------------

private class RerunRunConfigurationTool : BaseRunTool() {

    override val descriptor: McpToolDescriptor = rerunRunConfigurationDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return noProjectResult(RERUN_TOOL_NAME)
        val name = arguments.requiredString("configurationName") ?: return missingNameError()

        val settings = findSettingsByName(project, name) ?: return unknownConfiguration(project, name)

        // ① 平台级的中间态检查：IDE 在"启动中 / 停止中"会把 Rerun 按钮灰掉（`FakeRerunAction.isEnabled`
        //    判的就是 `isStarting()` 与 `isProcessTerminating()`），这里照做。
        val before = snapshotOf(project, settings)
        before.transientInstance?.let { busy ->
            return errorResult(
                "\"${settings.name}\" is currently ${if (busy.terminating) "stopping" else "starting"}, " +
                    "and the IDE disables Rerun in that state. Retry once it settles.",
                before.toJson(),
            )
        }

        // ② 插件级的并发守护：同一配置已有一次 rerun 在飞行中就直接报错。
        //    放行到平台的后果实测过：这次调用会挂约 20 秒，然后拿到传输层的 HTTP 404
        //    ——因为平台会为"对已在运行的配置再来一次"弹模态确认框，而无头路径上没人点它。
        val inFlight = project.service<RunLifecycleInFlight>()
        if (!inFlight.tryAcquire(settings.name)) {
            return errorResult(
                "A rerun of \"${settings.name}\" is already in progress. Wait for it to finish first.",
                before.toJson(),
            )
        }
        return try {
            rerunNow(
                project = project,
                settings = settings,
                stopTimeoutMs = arguments.optionalInt("stopTimeoutMs", defaultWaitTimeoutMs()),
                startTimeoutMs = arguments.optionalInt("startTimeoutMs", defaultStartTimeoutMs()),
            )
        } finally {
            inFlight.release(settings.name)
        }
    }

    /** 真正的重启流程。单独拆出来，是为了让上面的并发守护能用一个 `try/finally` 干净地包住它。 */
    private suspend fun rerunNow(
        project: Project,
        settings: RunnerAndConfigurationSettings,
        stopTimeoutMs: Int,
        startTimeoutMs: Int,
    ): McpToolCallResult {

        val startedAtNanos = System.nanoTime()
        val transition = mutableListOf<RunTransitionStep>()
        val warnings = mutableListOf<String>()

        // 第 1 步：停掉旧实例。这一步本身就是状态流转的第一个节点。
        val running = activeDescriptorsFor(project, settings.name)
        var stopped = 0
        if (running.isNotEmpty()) {
            val outcome = stopDescriptors(running, stopTimeoutMs, force = true)
            stopped = outcome.requestedStops
            if (outcome.stillRunning > 0) {
                warnings += "${outcome.stillRunning} instance(s) did not exit within ${stopTimeoutMs}ms; starting anyway."
            }
            if (outcome.forcedKills > 0) {
                warnings += "${outcome.forcedKills} instance(s) had to be killed."
            }
            transition += RunTransitionStep(
                phase = TransitionPhase.STOPPED,
                detail = describeStoppedInstances(stopped, outcome.forcedKills, outcome.instances),
                elapsedMs = elapsedSince(startedAtNanos),
            )
        }

        // 第 2 步：起新的。构造不出执行环境就是"什么都没做"。
        val environment = buildRunEnvironment(project, settings)
        if (environment == null) {
            val after = snapshotOf(project, settings)
            val failure = "The IDE could not build an execution environment for this configuration; nothing was started."
            return McpToolCallResult.text(
                "Could not start \"${settings.name}\": no runner can launch this configuration type.",
                RerunReport(
                    configurationName = settings.name,
                    mode = RerunMode.NO_OP,
                    stoppedInstances = stopped,
                    runningAfter = after.runningInstanceCount,
                    instances = after.instances,
                    warnings = warnings + failure,
                    started = false,
                    startFailure = "No runner can launch this configuration type.",
                    transition = transition,
                ).toJson(),
            )
        }

        // 关键：同步等到新进程真的被创建、并且状态收敛成"确实在跑"才返回，
        // 否则紧接着做快照必然读到 0 个在跑，调用方会误判成"启动失败"。
        val start = startRunAndAwait(project, environment, settings, startTimeoutMs)
        transition += RunTransitionStep(
            phase = if (start.started) TransitionPhase.STARTED else TransitionPhase.START_FAILED,
            detail = if (start.started) {
                "execution ${start.executionId}" + (start.pid?.let { ", pid $it" }.orEmpty())
            } else {
                start.failure ?: "unknown failure"
            },
            elapsedMs = start.startElapsedMs,
        )
        if (!start.started) {
            warnings += "The new process did not come up: ${start.failure}"
        }

        // 直接用启动过程收敛出的快照：再自己拍一次会读到中间态（见 awaitLiveState 的 KDoc）
        val after = start.state
        val report = RerunReport(
            configurationName = settings.name,
            mode = if (stopped > 0) RerunMode.RESTARTED else RerunMode.STARTED,
            stoppedInstances = stopped,
            runningAfter = after.runningInstanceCount,
            instances = after.instances,
            warnings = warnings,
            started = start.started,
            startFailure = start.failure,
            sessionId = start.sessionId,
            output = start.output,
            fullOutputPath = start.fullOutputPath,
            transition = transition,
        )
        return McpToolCallResult.text(summaryOf(report), report.toJson())
    }

    /** 人读的一行摘要：把"停旧的 → 起新的"和每步耗时讲清楚。 */
    private fun summaryOf(report: RerunReport): String = buildString {
        append(if (report.mode == RerunMode.RESTARTED) "Restarted \"" else "Started \"")
        append(report.configurationName).append('"')
        if (report.stoppedInstances > 0) {
            append(" (stopped ").append(report.stoppedInstances).append(" old instance(s) first)")
        }
        append(": ")
        if (report.started) {
            val startStep = report.transition.lastOrNull { it.phase == TransitionPhase.STARTED }
            append("new process is up")
            if (startStep != null) append(" after ").append(startStep.elapsedMs).append("ms")
            append(" — ").append(report.runningAfter).append(" live instance(s).")
        } else {
            append("the new process did not come up — ").append(report.startFailure).append('.')
        }
    }
}

/** 把"停了几个、退出码是多少、有没有强杀"压成一句可读的 detail。 */
private fun describeStoppedInstances(requested: Int, forcedKills: Int, instances: List<RunInstanceState>): String {
    val codes = instances.joinToString(", ") { it.exitCode?.toString() ?: "still running" }
    return "stop requested for $requested instance(s); forced kills: $forcedKills; exit codes: $codes"
}

/** 从 rerun 请求发出算起的毫秒数。 */
private fun elapsedSince(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000