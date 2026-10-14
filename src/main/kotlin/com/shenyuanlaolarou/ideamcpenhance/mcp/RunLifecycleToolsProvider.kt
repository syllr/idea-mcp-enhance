package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.projectOrNull
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
 * ## 注册方式
 *
 * 由 `META-INF/mcpserver.xml` 注册，该文件只在可选依赖 `com.intellij.mcpServer` 存在时才会被读取
 * （`<depends optional="true" config-file="mcpserver.xml">`）。没有 MCP Server 的 IDE 上，
 * 插件的 prompt 功能照常工作，这些类也不会被加载。
 */
class RunLifecycleToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(
        ListRunConfigurationsTool(),
        GetRunConfigurationStatusTool(),
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

    protected abstract suspend fun body(args: JsonObject): McpToolCallResult

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
// list_run_configurations_with_state
// ---------------------------------------------------------------------------

private class ListRunConfigurationsTool : BaseRunTool() {

    override val descriptor: McpToolDescriptor = listRunConfigurationsDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return noProjectResult(LIST_TOOL_NAME)
        val states = listRunConfigurationStates(project, arguments.optionalBoolean("includeTemplates", true))
        val running = states.count { it.running }

        return McpToolCallResult.text(
            "Found ${states.size} run configuration(s); $running currently running.",
            buildJsonObject {
                put("count", states.size)
                put("runningCount", running)
                putJsonArray("configurations") {
                    for (state in states) {
                        add(state.toJson())
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// get_run_configuration_status
// ---------------------------------------------------------------------------

private class GetRunConfigurationStatusTool : BaseRunTool() {

    override val descriptor: McpToolDescriptor = getRunConfigurationStatusDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return noProjectResult(STATUS_TOOL_NAME)
        val name = arguments.requiredString("configurationName") ?: return missingNameError()

        val settings = findSettingsByName(project, name) ?: return unknownConfiguration(project, name)
        val state = snapshotOf(project, settings)

        val summary = buildString {
            append('"').append(state.name).append("\" is ")
            append(if (state.running) "running" else "not running")
            append(" (").append(state.runningInstanceCount).append(" live instance(s)).")
            if (state.runningInstanceCount > 1) {
                append(" WARNING: more than one instance is live — this configuration was started more than once.")
            }
        }
        return McpToolCallResult.text(summary, state.toJson())
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
        val stopTimeoutMs = arguments.optionalInt("stopTimeoutMs", defaultWaitTimeoutMs())

        val warnings = mutableListOf<String>()
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
        }

        val environment = buildRunEnvironment(project, settings)
        if (environment == null) {
            val after = snapshotOf(project, settings)
            val report = RerunReport(
                configurationName = settings.name,
                mode = RerunMode.NO_OP,
                stoppedInstances = stopped,
                runningAfter = after.runningInstanceCount,
                instances = after.instances,
                warnings = warnings +
                    "The IDE could not build an execution environment for this configuration; nothing was started.",
            )
            return McpToolCallResult.text(
                "Could not start \"${settings.name}\": no runner can launch this configuration type.",
                report.toJson(),
            )
        }

        restartRunProfile(project, environment)

        val mode = if (stopped > 0) RerunMode.RESTARTED else RerunMode.STARTED
        val after = snapshotOf(project, settings)
        val report = RerunReport(
            configurationName = settings.name,
            mode = mode,
            stoppedInstances = stopped,
            runningAfter = after.runningInstanceCount,
            instances = after.instances,
            warnings = warnings,
        )
        return McpToolCallResult.text(
            when (mode) {
                RerunMode.RESTARTED -> "Restarted \"${settings.name}\" (stopped $stopped running instance(s) first)."
                RerunMode.STARTED -> "Started \"${settings.name}\" — it was not running."
                RerunMode.NO_OP -> "Nothing was done for \"${settings.name}\"."
            },
            report.toJson(),
        )
    }
}