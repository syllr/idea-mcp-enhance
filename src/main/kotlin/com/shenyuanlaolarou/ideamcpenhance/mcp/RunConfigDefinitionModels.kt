package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `inspect_run_configuration` 的**纯数据层**：一个配置的全貌 → JSON。
 *
 * 形状的取舍：
 *
 * - **没有派生计数** —— 实例数 = `instances` 的长度，历史数 = `history` 的长度。
 *   模型自己数比工具塞一个可能算错的数可靠。
 * - **null 键保留** —— sidecar 缺失时 `endedAt` / `durationMs` / `pid` / `exitCode` 都是 null，
 *   但键在（见 `AGENTS.md` 的约定：agent 要能区分"键不存在"和"值为 null"）。
 * - **日志只给路径** —— 历史日志是死的、不会再涨，agent 按路径自己读；
 *   只有还在跑的实例才需要"看一眼"的手段，而那个手段是"读那个正在写的文件"。
 */

/** 一个配置的完整快照。 */
internal data class RunConfigurationInspection(
    val configurationName: String,
    val typeId: String,
    val typeDisplayName: String,
    val storage: RunConfigurationStorage,
    val storagePath: String?,
    val shared: Boolean,
    val isTemplate: Boolean,
    val canRun: Boolean,
    val canRunBlockedReason: String?,
    val naming: RunConfigurationNaming?,
    val attributes: Map<String, String>,
    val options: Map<String, String>,
    val envs: Map<String, String>,
    val beforeLaunchSteps: List<RunBeforeLaunchStep>,
    val configurationXml: String?,
    val instances: List<RunInstanceState>,
    val history: List<RunLogEntry>,
    val warnings: List<String>,
)

/** 实例在 `inspect` 里的形状：比列表多两个时间字段。 */
internal fun RunInstanceState.toInspectionJson(nowMillis: Long): JsonObject = buildJsonObject {
    put("executionId", executionId)
    put("state", state)
    put("pid", pid)
    put("startedAt", startedAtMillis)
    put("startedAtIso", startedAtMillis?.let(::isoTimestamp))
    put("uptimeMs", startedAtMillis?.let { nowMillis - it })
    put("logFile", logFile)
}

/** 前置任务的形状。 */
internal fun RunBeforeLaunchStep.toJson(): JsonObject = buildJsonObject {
    put("type", type)
    put("referencedConfiguration", referencedConfiguration)
}

/** `inspect` 的完整 JSON。 */
internal fun RunConfigurationInspection.toJson(): JsonObject = buildJsonObject {
    put("configurationName", configurationName)
    put("type", typeId)
    put("typeDisplayName", typeDisplayName)
    putJsonObject("storage") {
        put("kind", storage.id)
        put("path", storagePath)
        put("shared", shared)
    }
    put("isTemplate", isTemplate)
    put("canRun", canRun)
    put("canRunBlockedReason", canRunBlockedReason)
    putJsonObject("naming") {
        val parsed = naming
        if (parsed == null) {
            put("platform", null)
            put("base", null)
            put("env", null)
        } else {
            put("platform", parsed.platform)
            put("base", parsed.base)
            put("env", parsed.env)
        }
    }
    putJsonObject("attributes") { attributes.forEach { (key, value) -> put(key, value) } }
    putJsonObject("options") { options.forEach { (key, value) -> put(key, value) } }
    putJsonObject("envs") { envs.forEach { (key, value) -> put(key, value) } }
    putJsonArray("beforeLaunchSteps") { beforeLaunchSteps.forEach { add(it.toJson()) } }
    put("configurationXml", configurationXml)
    putJsonArray("instances") {
        val now = System.currentTimeMillis()
        instances.forEach { add(it.toInspectionJson(now)) }
    }
    putJsonArray("history") { history.forEach { add(it.toJson()) } }
    putJsonArray("warnings") { warnings.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
}
