package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * MCP 工具的**纯数据层**：不含任何平台类型，也不在这里做 IO。
 *
 * 平台接线（`RunConfigAccess`）负责把 `RunContentDescriptor` / `ProcessHandler` 拍平成这里的
 * [RunInstanceState]；本文件只负责"数据 → JSON"。
 *
 * 这样切分的理由与 prompt 那边一致：agent 真正依赖的是**返回 JSON 的形状**，把它做成纯函数就能在
 * 纯 JUnit 里逐字段断言，不需要启动 IDE，也就不必启动一个 Run Configuration 去验证。
 */

/** 实例状态：`running`（进程在跑）。 */
internal const val INSTANCE_STATE_RUNNING = "running"

/** 实例状态：`terminating`（已请求停止，还没退出）。 */
internal const val INSTANCE_STATE_TERMINATING = "terminating"

/** 实例状态：`starting`（进程还没挂上，还在 before-launch / 启动中）。 */
internal const val INSTANCE_STATE_STARTING = "starting"

/**
 * 一个运行实例（一次 Run 执行）的实时状态。
 *
 * 注意一个配置可以对应**多个**实例：内置 MCP 的 `execute_run_configuration` 每调一次就 spawn 一个新进程，
 * 所以"这个配置在跑几个"本身就是个需要回答的问题。
 *
 * @param executionId IDE 分配的执行 id（[com.intellij.execution.ui.RunContentDescriptor.getExecutionId]）
 * @param displayName Run 工具窗口标签上的名字，通常等于配置名加序号
 * @param running 进程尚未退出（`!isProcessTerminated`）
 * @param terminating 已请求终止但尚未退出
 * @param startNotified IDE 已经收到"进程启动"通知；false 表示还卡在启动阶段（跑的是构建脚本，不是被测进程）
 * @param pid 操作系统进程号；拿不到时为 null（进程尚未真正创建）
 * @param exitCode 退出码；仍在运行时为 null
 * @param logFile 这次运行的日志文件路径；没采到时为 null（见 [RunLogAccess]）
 * @param startedAtMillis 这次运行的开始时刻；从日志戳里读（没采到日志时为 null）
 */
data class RunInstanceState(
    val executionId: Long,
    val displayName: String,
    val running: Boolean,
    val terminating: Boolean,
    val startNotified: Boolean,
    val pid: Long?,
    val exitCode: Int?,
    val logFile: String? = null,
    val startedAtMillis: Long? = null,
) {
    /** 是否处于"已经要求停、但还没停下来"的中间态——`stop` 超时后需要 force 的就是这些。 */
    val stuckTerminating: Boolean get() = terminating && !running

    /**
     * 是否落在"启动中 / 停止中"这类中间态。
     *
     * 这正是 IDE 的 **Rerun 按钮灰掉**的两种状态（见平台 `FakeRerunAction.isEnabled`：
     * `ExecutionEnvironmentProxy.isStarting()` 与 `ProcessHandler.isProcessTerminating()`）。
     */
    val inTransientState: Boolean get() = terminating || (running && !startNotified)

    /**
     * 三态。
     *
     * 列表工具用它替代 `running` / `terminating` / `startNotified` 三个布尔 ——
     * 三个布尔要靠组合判断，一个字段一眼就能看出"现在能不能 rerun"。
     */
    val state: String
        get() = when {
            terminating -> INSTANCE_STATE_TERMINATING
            running && !startNotified -> INSTANCE_STATE_STARTING
            else -> INSTANCE_STATE_RUNNING
        }
}

/**
 * 一个 Run Configuration 及其当前所有运行实例。
 *
 * @param uniqueId 模板与实例的唯一标识，用于在名字重复时消歧
 * @param instances 该配置当前存在的所有 Run 内容（可能为空 = 没跑过或已停）
 */
data class RunConfigurationState(
    val name: String,
    val typeId: String,
    val isTemplate: Boolean,
    val uniqueId: String,
    val instances: List<RunInstanceState>,
) {
    /** 是否至少有一个活着的实例。 */
    val running: Boolean get() = instances.any { it.running }

    /** 活着的实例个数。>1 就说明重复启动了（端口漂移的根因）。 */
    val runningInstanceCount: Int get() = instances.count { it.running }

    /** 处于"启动中 / 停止中"中间态的第一个实例；没有就是 null。 */
    val transientInstance: RunInstanceState? get() = instances.firstOrNull { it.inTransientState }
}

/**
 * rerun 的**前置拒绝原因**（`null` = 可以继续）。纯函数，便于单测。
 *
 * 两个来源：
 * - [transient]：快照里已经能看到的中间态实例（启动中 / 停止中）——与 GUI 判断 Rerun 按钮可用的那两种状态同源
 * - [hasPendingRunContent]：Run 内容已经创建、但进程还没挂上。**快照看不到它**
 *   （`toInstanceState` 对 `processHandler == null` 返回 null，那条会被整个丢掉），
 *   而"人手动按下 Run 之后的那一小段"恰好落在这一档上，所以必须单独判一次
 *
 * 注意：这里刻意**不**维护"本插件正在 rerun 哪个配置"的内存标志。理由见 `SPEC.md` 的可重入条款：
 * 那种标志只挡得住"我们自己并发调用"，挡不住用户去点 GUI，而且覆盖窗口只有几毫秒，
 * 为它引入一份需要小心释放的状态不划算。
 */
internal fun rerunBlockedReason(
    configurationName: String,
    transient: RunInstanceState?,
    hasPendingRunContent: Boolean,
): String? = when {
    transient != null -> "\"$configurationName\" is currently ${if (transient.terminating) "stopping" else "starting"}, " +
        "and the IDE disables Rerun in that state. Retry once it settles."

    hasPendingRunContent -> "\"$configurationName\" is currently starting (its process is not up yet), " +
        "and the IDE disables Rerun in that state. Retry once it settles."

    else -> null
}


/**
 * "平台正在等用户交互（模态对话框占着 EDT）"时的说明文案（纯函数，便于单测）。
 *
 * 这是**用户的卡点**，不是瞬时故障：工具自己重试多少次都没用，必须由 AI 转告用户去 IDE 里处理。
 * 所以文案 MUST 明确点名这一点，并给出"处理完再调一次"的下一步；同时提醒重试前先查状态
 * （排队中的提交可能稍后才真的发生）。
 *
 * 刻意**不带毫秒数**：主动判断（`modalDialogBlockingUser`）时根本没有等，带上时间只会误导。
 */
internal fun userMustActReason(configurationName: String): String =
    "This requires the user to act in the IDE: the IDE's UI thread is busy, most likely waiting for the user " +
        "(a modal dialog such as \"Edit Configuration\", or a settings window they opened), so it cannot start " +
        "\"$configurationName\" for you right now. " +
        "Tell the user to finish that dialog in the IDE (fix the configuration, then press Run — or press Cancel), " +
        "and call this tool again afterwards. Before retrying, check `list_running_configurations`: " +
        "a request that was already queued may still be delivered late."

/**
 * 停完旧实例之后**能不能把新进程启起来**（`null` = 可以）。
 *
 * 没停干净就坚决不启：那正是"同一配置留两个实例"的病根，也正是平台会弹出
 * "重启需要确认"模态框的条件（在 MCP 无头路径上没人点它 → 调用挂到传输层超时）。
 */
internal fun startBlockedReason(configurationName: String, stillRunning: Int): String? =
    if (stillRunning <= 0) {
        null
    } else {
        "\"$configurationName\" still has $stillRunning instance(s) alive after the stop request, " +
            "so no new process was started — that is exactly the state that would leave two instances " +
            "of the same configuration running. Stop it first, or check what is holding it."
    }

/**
 * 只留活实例的投影。
 *
 * 给"只报现在什么在跑"的 `list_running_configurations` 用。切完之后返回里就不含任何"不在跑"的东西：
 * 已结束的 Run 内容（Run 工具窗口留下的标签页）不再出现，于是 `running` 恒为 true、`exitCode` 恒为 null，
 * 而 `runningInstanceCount` 也就等价于 `instances` 的长度。
 *
 * 与 `stop` / `rerun` 的区别是刻意的：那两个工具要报告"它实际动过哪些实例"，所以保留已结束的记录
 * （退出码正是"停干净了没有"的证据）；而 list 回答的是"现在什么在跑"。
 */
fun RunConfigurationState.onlyRunningInstances(): RunConfigurationState =
    copy(instances = instances.filter { it.running })

/** `stop_run_configuration` 的结果。 */
data class StopReport(
    val configurationName: String,
    /** 匹配到的实例总数（含已结束的残留标签页）。 */
    val matchedInstances: Int,
    /** 真正发起过停止请求的实例数。 */
    val requestedStops: Int,
    /** 等待超时后被强制 kill 的实例数。 */
    val forcedKills: Int,
    /** 超时等待后是否仍有实例没停下来。 */
    val timedOut: Boolean,
    val instances: List<RunInstanceState>,
    val warnings: List<String>,
)

/** `rerun_run_configuration` 的结果。 */
data class RerunReport(
    val configurationName: String,
    /** 这次 rerun 走的是哪条路径，见 [RerunMode]。 */
    val mode: RerunMode,
    /** 重启前先停掉的实例数。 */
    val stoppedInstances: Int,
    /** 新进程起来**并且注册进 Run 内容列表之后**，仍在跑的实例数。 */
    /**
     * 新进程起来后仍在跑的实例数；`null` 表示**等窗口用完也没确认**，不是 0。
     *
     * 这里刻意不用 0：提交后立刻拍到的快照必然是 0，把它当结果回报会重演
     * "`runningAfter` 恒为 0 却写着已启动"那个自相矛盾的老毛病。
     */
    val runningAfter: Int?,
    val instances: List<RunInstanceState>,
    val warnings: List<String>,
    /**
     * 新进程是否真的起来了。
     *
     * - `true` / `false`：等到了结果（false 时看 [startFailure]，不要只看 [mode]）
     * - `null`：**不知道** —— 启动请求已提交，但等窗口内没确认进程起来，
     *   进程可能还在 before-launch 阶段。与仓库约定一致："值为 null = 进程还没起来"
     */
    val started: Boolean? = false,
    /** 启动失败的原因；成功时为 null。 */
    val startFailure: String? = null,
    /** 运行会话标识，字段语义与内置 `execute_run_configuration` 的 `sessionId` 一致。 */
    val sessionId: String? = null,
    /** 新进程输出的内联预览（最多 10000 字符，超出补 `<truncated>`），与官方 `output` 一致。 */
    val output: String = "",
    /** 完整日志文件路径，进程运行期间会持续增长，与官方 `fullOutputPath` 一致。 */
    val fullOutputPath: String? = null,
    /** 这次 rerun 的状态流转，见 [RunTransitionStep]。 */
    val transition: List<RunTransitionStep> = emptyList(),
)

/**
 * rerun 过程中的一个状态节点。
 *
 * 单独给出来是因为"重启"是个多步过程（先停旧的、再起新的、新的到底起没起），
 * 只给一个最终结果，调用方无法判断卡在了哪一步、每步花了多久。
 *
 * @param elapsedMs 从发出 rerun 请求到走到这个节点所经过的毫秒数
 */
data class RunTransitionStep(
    val phase: TransitionPhase,
    val detail: String,
    val elapsedMs: Long,
)

/** 状态流转的节点类型。 */
enum class TransitionPhase(val jsonValue: String) {
    /** 旧实例已停（实例数、退出码写在 detail 里）。 */
    STOPPED("STOPPED"),

    /** 新进程已创建并注册。 */
    STARTED("STARTED"),

    /**
     * 启动请求已提交，但等窗口内**没有确认它起来**。
     *
     * 与 [STARTED] / [START_FAILED] 的区别是"**不知道**"：提交后立刻返回，
     * 进程可能还在 before-launch（构建 / 另一个配置）阶段，也可能刚起来。
     * 真实状态要用 `list_running_configurations` 查。
     */
    START_SUBMITTED("START_SUBMITTED"),

    /** 启动请求发出但没起来（IDE 拒绝 / 超时）。 */
    START_FAILED("START_FAILED"),
}

/**
 * rerun 的实际路径。
 *
 * 三个取值对应 IDE 里 Rerun 按钮的三种可观察行为，agent 可以据此判断"这次到底是重启了还是新起了"。
 *
 * 注意这只是"**发起了什么**"，"**新进程到底起没起**"看 [RerunReport.started]。
 */
enum class RerunMode(val jsonValue: String) {
    /** 原本在跑 → 已停掉旧实例再启动，与界面 Rerun 一致。 */
    RESTARTED("RESTARTED"),

    /** 原本没在跑 → 直接启动。 */
    STARTED("STARTED"),

    /** 连启动都发不出去（构造不出 ExecutionEnvironment）→ 什么都没做。 */
    NO_OP("NO_OP"),
}

/** 找不到配置时的结果。`available` 只给名字，方便 agent 自己纠正下一次调用。 */
data class UnknownConfigurationReport(
    val requestedName: String,
    val availableNames: List<String>,
) {
    /**
     * 错误文案里带上候选名。
     *
     * agent 拿到"没找到"若不知道有哪些候选，就只能反复瞎猜配置名；给出候选能把重试收敛到一次。
     */
    val message: String
        get() = buildString {
            append("Run configuration \"")
            append(requestedName)
            append("\" not found in this project.")
            if (availableNames.isEmpty()) {
                append(" The project has no run configurations at all.")
            } else {
                append(" Available names: ")
                append(availableNames.joinToString(", "))
            }
        }
}

// ---------------------------------------------------------------------------
// 数据 → JSON
// ---------------------------------------------------------------------------

/**
 * 统一约定：
 *
 * - `put(key, null)` 在 kotlinx.serialization 里会写出 JSON `null`，而不是省略该键。
 *   这里**故意保留** null 键：agent 判断"有没有 pid"时要能区分"键不存在"和"值为 null"，
 *   前者通常意味着工具出错了。
 * - 数组一律输出 `[]` 而非省略，理由同上。
 */

internal fun RunInstanceState.toJson(): JsonObject = buildJsonObject {
    put("executionId", executionId)
    put("displayName", displayName)
    put("running", running)
    put("terminating", terminating)
    put("startNotified", startNotified)
    put("pid", pid)
    put("exitCode", exitCode)
}

internal fun List<RunInstanceState>.instancesToJson(): JsonArray = buildJsonArray {
    forEach { add(it.toJson()) }
}

internal fun RunConfigurationState.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("type", typeId)
    put("isTemplate", isTemplate)
    put("running", running)
    put("runningInstanceCount", runningInstanceCount)
    putJsonArray("instances") {
        for (instance in instances) {
            add(instance.toJson())
        }
    }
}

/**
 * 列表工具用的**精简**形状。
 *
 * 列表是**索引**，不是详情：它只回答"谁在跑、几个实例、各自的 pid / 状态 / 日志在哪"。
 * 定义详情（options / envs / XML）、历史实例、日志内容都走 `inspect_run_configuration`。
 *
 * 相比完整形状，这里刻意去掉：
 *
 * - `isTemplate` / `running` —— 列表已经过滤到"有活实例"，这两个恒为 false / true
 * - `exitCode` —— 活着的进程没有退出码，恒为 null
 * - `displayName` —— Run 标签页的 UI 文本，多实例会带后缀，不能当标识
 * - 各种**派生计数** —— 数组长度就是计数，不重复给
 */
internal fun RunInstanceState.toListJson(): JsonObject = buildJsonObject {
    put("executionId", executionId)
    put("state", state)
    put("pid", pid)
    put("logFile", logFile)
}

/** 列表工具的配置级形状。见 [toListJson]。 */
internal fun RunConfigurationState.toListJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("type", typeId)
    putJsonArray("instances") {
        for (instance in instances) {
            add(instance.toListJson())
        }
    }
}

internal fun StopReport.toJson(): JsonObject = buildJsonObject {
    put("configurationName", configurationName)
    put("matchedInstances", matchedInstances)
    put("requestedStops", requestedStops)
    put("forcedKills", forcedKills)
    put("timedOut", timedOut)
    put("allTerminated", instances.none { it.running })
    putJsonArray("instances") {
        for (instance in instances) {
            add(instance.toJson())
        }
    }
    putJsonArray("warnings") {
        for (warning in warnings) {
            add(JsonPrimitive(warning))
        }
    }
}

internal fun RunTransitionStep.toJson(): JsonObject = buildJsonObject {
    put("phase", phase.jsonValue)
    put("detail", detail)
    put("elapsedMs", elapsedMs)
}

internal fun RerunReport.toJson(): JsonObject = buildJsonObject {
    put("configurationName", configurationName)
    put("mode", mode.jsonValue)
    put("started", started)
    put("startFailure", startFailure)
    put("stoppedInstances", stoppedInstances)
    put("runningAfter", runningAfter)
    put("sessionId", sessionId)
    put("output", output)
    put("fullOutputPath", fullOutputPath)
    putJsonArray("transition") {
        for (step in transition) {
            add(step.toJson())
        }
    }
    putJsonArray("instances") {
        for (instance in instances) {
            add(instance.toJson())
        }
    }
    putJsonArray("warnings") {
        for (warning in warnings) {
            add(JsonPrimitive(warning))
        }
    }
}

internal fun UnknownConfigurationReport.toJson(): JsonObject = buildJsonObject {
    put("configurationName", requestedName)
    putJsonArray("availableNames") {
        for (name in availableNames) {
            add(JsonPrimitive(name))
        }
    }
}
