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
 */
data class RunInstanceState(
    val executionId: Long,
    val displayName: String,
    val running: Boolean,
    val terminating: Boolean,
    val startNotified: Boolean,
    val pid: Long?,
    val exitCode: Int?,
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
    val runningAfter: Int,
    val instances: List<RunInstanceState>,
    val warnings: List<String>,
    /** 新进程是否真的起来了。false 时看 [startFailure]，不要只看 [mode]。 */
    val started: Boolean = false,
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