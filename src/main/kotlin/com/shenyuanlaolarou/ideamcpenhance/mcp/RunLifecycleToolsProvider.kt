package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
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
import java.nio.file.Files
import java.nio.file.Path
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
        CreateRunConfigurationTool(),
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

        var descriptors = activeDescriptorsFor(project, settings.name)
        // "启动在飞"（异步提交刚返回、或别的工具/人手刚发起）：此时直接报"没在跑"是假话——
        // Run 内容可能还没建、进程还没挂上，但它随后就会起来。先有界地等它挂上，再照常停。
        var pendingStartWarning: String? = null
        if (descriptors.isEmpty() && startInFlight(project, settings)) {
            if (awaitPendingStart(project, settings, PENDING_START_ATTACH_TIMEOUT_MS)) {
                descriptors = activeDescriptorsFor(project, settings.name)
            } else {
                pendingStartWarning =
                    "A start of \"${settings.name}\" is already in flight but has not produced a process yet " +
                        "(nothing was stopped). It may come up shortly — call this tool again, or check " +
                        "`list_running_configurations`, to confirm."
            }
        }

        if (descriptors.isEmpty()) {
            val idle = StopReport(
                configurationName = settings.name,
                matchedInstances = 0,
                requestedStops = 0,
                forcedKills = 0,
                timedOut = false,
                instances = emptyList(),
                warnings = listOfNotNull(pendingStartWarning),
            )
            return McpToolCallResult.text(
                pendingStartWarning ?: "\"${settings.name}\" is not running; nothing to stop.",
                idle.toJson(),
            )
        }

        val waitTimeoutMs = arguments.optionalInt("waitTimeoutMs", defaultWaitTimeoutMs())
        val force = arguments.optionalBoolean("force", true)
        val outcome = stopDescriptors(descriptors, waitTimeoutMs, force)

        val warnings = buildList {
            if (pendingStartWarning != null) add(pendingStartWarning)
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

private class CreateRunConfigurationTool : BaseRunTool() {

    override val descriptor: McpToolDescriptor = createRunConfigurationDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return noProjectResult(CREATE_TOOL_NAME)
        val name = arguments.requiredString("configurationName") ?: return missingNameError()
        val scriptPath = arguments.optionalString("scriptPath")
        val scriptText = arguments.optionalString("scriptText")
        val cloneFromName = arguments.optionalString("cloneFrom")

        val given = listOfNotNull(
            scriptPath?.let { "scriptPath" },
            scriptText?.let { "scriptText" },
            cloneFromName?.let { "cloneFrom" },
        )
        if (given.size != 1) {
            return errorResult(
                "Give exactly one of `scriptPath` / `scriptText` / `cloneFrom` " +
                    "(got: ${if (given.isEmpty()) "none" else given.joinToString(" + ")}).",
                null,
            )
        }

        // 名字必须自由：平台自己不做唯一化，重名会让两个配置同名（`findConfigurationByName` 之后只能拿到一个）
        RunManager.getInstance(project).findConfigurationByName(name)?.let {
            val suggestion = RunManager.getInstance(project).suggestUniqueName(name, null)
            return errorResult(
                "A run configuration named \"$name\" already exists. Nothing was created — " +
                    "call this tool again with a free name such as \"$suggestion\".",
                null,
            )
        }

        val storagePath = runConfigurationFilePath(project, name)
            ?: return errorResult(
                "Could not resolve the project root, so there is nowhere to store the new configuration.",
                null,
            )

        // 克隆源：必须存在、不是模板、且本身就是 Shell 类型
        val source = cloneFromName?.let { requested ->
            val settings = findSettingsByName(project, requested)
                ?: return unknownConfiguration(project, requested)
            if (settings.isTemplate) {
                return errorResult(
                    "\"${settings.name}\" is a template, not a real configuration, so it cannot be cloned. " +
                        "Pick a concrete configuration (see `get_run_configurations`).",
                    null,
                )
            }
            if (settings.type.id != SHELL_CONFIGURATION_TYPE_ID) {
                return errorResult(
                    "\"${settings.name}\" is a ${settings.type.displayName} configuration, not a Shell one — " +
                        "this tool only creates Shell Script configurations, so it can only clone Shell ones. " +
                        "Use `scriptPath` / `scriptText` to create a fresh Shell configuration instead.",
                    null,
                )
            }
            settings
        }

        // 目标文件已存在就拒绝：没有 delete 工具，不悄悄覆盖用户在 IDE 里改好的文件
        if (Files.exists(Path.of(storagePath))) {
            return errorResult(
                "A file already exists at $storagePath, so a configuration named \"$name\" is already there. " +
                    "Nothing was created or overwritten — delete it in the IDE (there is deliberately no delete " +
                    "tool) or pick another name.",
                null,
            )
        }

        val warnings = mutableListOf<String>()

        // 文件内容：克隆 = 读源文件改名；新建 = 按 spec 生成（纯字符串操作，不经过平台 API）
        val content: String
        val createdFrom: String
        val clonedSteps: Int
        if (source != null) {
            val sourcePath = source.pathIfStoredInArbitraryFileInProject
                ?: return errorResult(
                    "\"${source.name}\" is not stored as a project file, so there is no file to copy. " +
                        "This tool only clones configurations stored under `.run/`.",
                    null,
                )
            val sourceXml = runCatching { Files.readString(Path.of(sourcePath)) }.getOrNull()
                ?: return errorResult(
                    "Could not read \"${source.name}\" from $sourcePath, so nothing was created.",
                    null,
                )
            content = cloneRunConfigurationXml(sourceXml, source.name, name)
                ?: return errorResult(
                    "Could not find the configuration tag in $sourcePath, so nothing was created.",
                    null,
                )
            createdFrom = "clone:${source.name}"
            clonedSteps = source.configuration.beforeRunTasks.size
            if (clonedSteps > 0) {
                warnings += "Its $clonedSteps before-launch step(s) were copied from " +
                    "\"${source.name}\", so starting it will run those first."
            }
        } else {
            val spec = ShellRunSpec(
                scriptPath = scriptPath,
                scriptText = scriptText,
                scriptOptions = arguments.optionalString("scriptOptions"),
                workingDirectory = arguments.optionalString("workingDirectory"),
                interpreterPath = arguments.optionalString("interpreterPath"),
                interpreterOptions = arguments.optionalString("interpreterOptions"),
                envs = arguments.stringMap("envs"),
            )
            if (scriptPath != null && !scriptFileExists(project, scriptPath)) {
                warnings += "The script file \"$scriptPath\" does not exist yet — " +
                    "the configuration is still created, but it cannot run until the file exists."
            }
            content = shellRunConfigurationXml(name, spec)
            createdFrom = if (scriptPath != null) "script:$scriptPath" else "script-text"
            clonedSteps = 0
        }

        if (!writeRunConfigurationFile(storagePath, content)) {
            return errorResult(
                "Could not write $storagePath, so nothing was created.",
                null,
            )
        }

        // 等 IDE 的文件监听注册它；注册上就用平台自己的 checkSettings 问"能不能跑"
        val registered = awaitConfigurationRegistered(project, name)
        val blocked = registered?.let { cannotRunReason(it, null) }
        if (registered == null) {
            warnings += "The file was written but the IDE has not loaded it yet — " +
                "check again shortly with `list_running_configurations`."
        }
        val report = CreateReport(
            configurationName = name,
            type = registered?.type?.id ?: SHELL_CONFIGURATION_TYPE_ID,
            typeDisplayName = registered?.type?.displayName ?: "Shell Script",
            createdFrom = createdFrom,
            storagePath = storagePath,
            uniqueID = registered?.uniqueID ?: "Shell Script.$name",
            canRun = blocked == null && registered != null,
            canRunBlockedReason = blocked ?: if (registered == null)
                "The file was written but the IDE has not loaded it yet, so this cannot be checked right now."
            else null,
            beforeLaunchSteps = registered?.configuration?.beforeRunTasks?.size ?: clonedSteps,
            warnings = warnings,
        )
        return McpToolCallResult.text(
            "Created Shell configuration \"$name\" at $storagePath. " +
                if (registered == null) "The IDE has not loaded it yet — check again shortly."
                else if (blocked == null) "It can run right now."
                else "It cannot run yet: $blocked",
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

        // 前置判定 ①：中间态就不许 rerun —— 与 IDE 把 Rerun 按钮灰掉的那两种状态同源
        // （`FakeRerunAction.isEnabled` 判 `isStarting()` / `isProcessTerminating()`）。
        // 这一层覆盖"任何已可见的中间态"，因此**也包括用户在 IDE 里手动点出来**的运行。
        val before = snapshotOf(project, settings)
        rerunBlockedReason(
            configurationName = settings.name,
            transient = before.transientInstance,
            hasPendingRunContent = hasPendingRunContent(project, settings.name),
        )?.let { reason ->
            return errorResult(reason, before.toJson())
        }

        // 前置判定 ② 在平台侧：见 `startRunAndAwait`（用平台自己的 `isStarting(environment)` 门）。
        return rerunNow(
            project = project,
            settings = settings,
            stopTimeoutMs = arguments.optionalInt("stopTimeoutMs", defaultWaitTimeoutMs()),
            startTimeoutMs = arguments.optionalInt("startTimeoutMs", defaultStartTimeoutMs()),
            waitForStart = arguments.optionalBoolean("waitForStart", true),
        )
    }

    /** 真正的重启流程。单独拆出来，是为了让上面的两层前置判定能用一个 `try/finally` 干净地包住它。 */
    private suspend fun rerunNow(
        project: Project,
        settings: RunnerAndConfigurationSettings,
        stopTimeoutMs: Int,
        startTimeoutMs: Int,
        waitForStart: Boolean,
    ): McpToolCallResult {

        val startedAtNanos = System.nanoTime()
        val transition = mutableListOf<RunTransitionStep>()
        val warnings = mutableListOf<String>()

        // 第 0 步：先把"能不能启动"问清楚，**再决定要不要停旧的**。
        // 顺序很重要：如果先停后判，用户在跑的应用会被白白杀掉，然后才被告知"需要你去 IDE 处理"
        // （模态对话框占着 EDT / 配置本身跑不起来 / 别人正在启动它）。
        val environment = buildRunEnvironment(project, settings)
        if (environment == null) {
            val after = snapshotOf(project, settings)
            val failure = "The IDE could not build an execution environment for this configuration; nothing was started."
            return McpToolCallResult.text(
                "Could not start \"${settings.name}\": no runner can launch this configuration type.",
                RerunReport(
                    configurationName = settings.name,
                    mode = RerunMode.NO_OP,
                    stoppedInstances = 0,
                    runningAfter = after.runningInstanceCount,
                    instances = after.instances,
                    warnings = warnings + failure,
                    started = false,
                    startFailure = "No runner can launch this configuration type.",
                    transition = transition,
                ).toJson(),
            )
        }
        startBlockedByPlatformReason(project, environment, settings)?.let { reason ->
            // 什么都还没做（没停、没启），所以直接按"前置判定"的形式给错误结果
            return errorResult(reason, snapshotOf(project, settings).toJson())
        }

        // 第 1 步：停掉旧实例。这一步本身就是状态流转的第一个节点。
        val running = activeDescriptorsFor(project, settings.name)
        var stopped = 0
        if (running.isNotEmpty()) {
            val outcome = stopDescriptors(running, stopTimeoutMs, force = true)
            stopped = outcome.requestedStops
            if (outcome.forcedKills > 0) {
                warnings += "${outcome.forcedKills} instance(s) had to be killed."
            }
            transition += RunTransitionStep(
                phase = TransitionPhase.STOPPED,
                detail = describeStoppedInstances(stopped, outcome.forcedKills, outcome.instances),
                elapsedMs = elapsedSince(startedAtNanos),
            )

            // 没停干净就坚决不启新的：那正是"同一配置留两个实例"的病根，
            // 也正是平台会弹出"重启需要确认"模态框的条件（无头路径上没人点它 → 调用挂到传输层超时）。
            startBlockedReason(settings.name, outcome.stillRunning)?.let { reason ->
                val after = snapshotOf(project, settings)
                transition += RunTransitionStep(
                    phase = TransitionPhase.START_FAILED,
                    detail = reason,
                    elapsedMs = elapsedSince(startedAtNanos),
                )
                return McpToolCallResult.text(
                    reason,
                    RerunReport(
                        configurationName = settings.name,
                        mode = RerunMode.RESTARTED,
                        stoppedInstances = stopped,
                        runningAfter = after.runningInstanceCount,
                        instances = after.instances,
                        warnings = warnings + reason,
                        started = false,
                        startFailure = reason,
                        transition = transition,
                    ).toJson(),
                )
            }
        }

        // 第 2 步：起新的（执行环境在第 0 步已经构建好并验过）。
        // 关键：默认同步等到新进程真的被创建、并且状态收敛成"确实在跑"才返回，
        // 否则紧接着做快照必然读到 0 个在跑，调用方会误判成"启动失败"。
        // `waitForStart = false` 时只提交、不等：那种配置的 before-launch 比服务器的会话耐心还长，
        // 等下去只会把结果丢成空 404；提交完让调用方自己轮询 `list_running_configurations` 才是可行解。
        val start = startRunAndAwait(project, environment, settings, startTimeoutMs, waitForStart = waitForStart)
        transition += RunTransitionStep(
            phase = when {
                !start.awaited -> TransitionPhase.START_SUBMITTED
                start.started -> TransitionPhase.STARTED
                else -> TransitionPhase.START_FAILED
            },
            detail = when {
                !start.awaited ->
                    "start request submitted, not awaited (waitForStart = false); " +
                        "the IDE creates the Run content only after the before-launch steps finish"
                start.started -> "execution ${start.executionId}" + (start.pid?.let { ", pid $it" }.orEmpty())
                else -> start.failure ?: "unknown failure"
            },
            elapsedMs = start.startElapsedMs,
        )
        if (!start.awaited) {
            warnings += NOT_AWAITED_WARNING
        } else if (!start.started) {
            warnings += "The new process did not come up: ${start.failure}"
        }

        // 直接用启动过程收敛出的快照：再自己拍一次会读到中间态（见 awaitLiveState 的 KDoc）
        val after = start.state
        val report = RerunReport(
            configurationName = settings.name,
            mode = if (stopped > 0) RerunMode.RESTARTED else RerunMode.STARTED,
            stoppedInstances = stopped,
            // 没等就用 null：提交后立刻拍到的快照必然是 0，把它当结果回报会重演那个自相矛盾的老毛病
            runningAfter = if (start.awaited) after.runningInstanceCount else null,
            instances = after.instances,
            warnings = warnings,
            started = if (start.awaited) start.started else null,
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
        when (report.started) {
            true -> {
                val startStep = report.transition.lastOrNull { it.phase == TransitionPhase.STARTED }
                append("new process is up")
                if (startStep != null) append(" after ").append(startStep.elapsedMs).append("ms")
                append(" — ").append(report.runningAfter).append(" live instance(s).")
            }
            // 失败原因本身就是完整句子（通常已带句点），这里统一收口，避免出现 ".."
            false -> append("the new process did not come up — ").append(report.startFailure.orEmpty().trimEnd('.')).append('.')
            // 没等：必须说清"不知道"，而不是暗示失败——否则 agent 会去重试，反而起出第二个实例
            null -> append(
                "the start was submitted but not awaited — the outcome is unknown; " +
                    "poll `list_running_configurations` to see when the process is up.",
            )
        }
    }
}

/**
 * `waitForStart = false` 时的固定警告：把"不知道"说清楚。
 *
 * 必须出现在 `warnings[]` 里，因为 `started = null` 是个新状态，调用方（尤其是 agent）
 * 很容易把它当成失败去重试——那会撞上平台的启动门，或者干脆起出第二个实例。
 */
private const val NOT_AWAITED_WARNING =
    "The start was submitted but not awaited (waitForStart = false): the outcome is unknown until the " +
        "before-launch steps finish. Poll `list_running_configurations` to see when the process is up."

/** 把"停了几个、退出码是多少、有没有强杀"压成一句可读的 detail。 */
private fun describeStoppedInstances(requested: Int, forcedKills: Int, instances: List<RunInstanceState>): String {
    val codes = instances.joinToString(", ") { it.exitCode?.toString() ?: "still running" }
    return "stop requested for $requested instance(s); forced kills: $forcedKills; exit codes: $codes"
}

/** 从 rerun 请求发出算起的毫秒数。 */
private fun elapsedSince(fromNanos: Long): Long = (System.nanoTime() - fromNanos) / 1_000_000