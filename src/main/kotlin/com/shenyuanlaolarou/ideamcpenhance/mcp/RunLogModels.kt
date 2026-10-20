package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant

/**
 * 运行日志的**纯数据层**：文件名规则、键的算法、条目的 JSON 形状。
 *
 * 这里没有平台类型、没有 IO —— 键怎么算、文件名怎么拼、历史条目怎么排序，
 * 全部是可以逐字段断言的纯函数（见 `RunLogSchemaTest`）。
 *
 * ## 为什么日志文件是"那次跑的记录本体"
 *
 * 平台侧不留运行历史：Run 标签页一关，`RunManager` / `ExecutionManager` 就什么都不剩了。
 * 所以"这个配置上次跑出来什么日志"只能靠我们自己写在盘上的痕迹回答。
 *
 * 因此约定：
 *
 * - **日志文件是本体** —— 只要文件还在，就能回答"那次跑留下了什么"
 * - **sidecar（`.json`）只是富化** —— 进程终止时才写（那时才知道 exitCode），
 *   丢了也不影响"日志还在就返回"，那一条历史就只剩 `startedAt` + 日志路径
 *
 * ## 为什么键是哈希而不是可读的名字
 *
 * 官方 `execute_run_configuration` 用 `sanitizeFileName` 处理名字，那是**不可逆**的：
 * `console-web` 与 `console_web` 会被抹成同一个名字，然后我们会把别人的日志
 * 当成这个配置的返回。所以这里用 `sha256(项目路径 + "\0" + 配置名)` 的前 16 位十六进制：
 *
 * - 精确：不同名字不会撞
 * - 只含小写字母数字：**大小写不敏感的 macOS 文件系统上也不会撞名**
 * - 带项目路径：两个项目里同名的配置不会混在一起
 */

/** 日志文件名前缀。与官方的 `ij_run__` 刻意区分开，扫目录时不会把官方的文件认成自己的。 */
internal const val RUN_LOG_FILE_PREFIX = "ij_enhance_run__"

/** 日志文件后缀。 */
internal const val RUN_LOG_FILE_SUFFIX = ".log"

/** sidecar（富化信息）后缀。 */
internal const val RUN_LOG_SIDECAR_SUFFIX = ".json"

/** 单个日志文件的大小上限（8 MB）。超过就不再写，并在条目上标注 `truncated`。 */
internal const val RUN_LOG_SIZE_LIMIT_BYTES = 8L * 1024 * 1024

/** 日志的最长保留时间（7 天）。超过的在下一次清理时被删掉。 */
internal const val RUN_LOG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

/**
 * 算"哪个项目的哪个配置"的键。
 *
 * 纯函数：同样的入参永远得到同样的键 —— 历史日志要跨进程、跨 IDE 会话被找到，
 * 键必须可重算，不能靠内存里的一张表。
 */
internal fun runLogKey(projectPath: String, configurationName: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest("$projectPath\u0000$configurationName".toByteArray(Charsets.UTF_8))
    return buildString {
        for (i in 0 until 8) {
            append(HEX[(bytes[i].toInt() and 0xFF) shr 4])
            append(HEX[(bytes[i].toInt() and 0xFF) and 0x0F])
        }
    }
}

/** 小写十六进制表。 */
private const val HEX = "0123456789abcdef"

/**
 * 日志文件名：`ij_enhance_run__<key>_<startedAtMillis>_<rand4>.log`。
 *
 * - `key`：见 [runLogKey]
 * - `startedAtMillis`：**排序键**。历史条目按它倒序，agent 第一眼看到的就是最近一次
 * - `rand4`：只为防文件名撞车 —— 复合配置会在**同一毫秒**起多个成员，不带随机后缀会互相覆盖
 */
internal fun runLogFileName(key: String, startedAtMillis: Long, randomSuffix: String): String =
    "${RUN_LOG_FILE_PREFIX}${key}_${startedAtMillis}_$randomSuffix$RUN_LOG_FILE_SUFFIX"

/** sidecar 文件名：与日志同名、换后缀。 */
internal fun runLogSidecarFileName(logFileName: String): String =
    logFileName.removeSuffix(RUN_LOG_FILE_SUFFIX) + RUN_LOG_SIDECAR_SUFFIX

/** 从日志文件名里解析出来的信息。 */
internal data class ParsedRunLogFileName(
    val key: String,
    val startedAtMillis: Long,
    val randomSuffix: String,
)

/**
 * 解析日志文件名。**认不出来就返回 null**（不猜、不抛）。
 *
 * 只认自己写的那种形状：前缀 + 16 位十六进制键 + 13 位时间戳 + 4 位随机后缀。
 * 官方的 `ij_run__*` 文件、以及别人放在同一个目录里的东西，一律不认。
 */
internal fun parseRunLogFileName(fileName: String): ParsedRunLogFileName? {
    if (!fileName.startsWith(RUN_LOG_FILE_PREFIX) || !fileName.endsWith(RUN_LOG_FILE_SUFFIX)) return null
    val middle = fileName.removePrefix(RUN_LOG_FILE_PREFIX).removeSuffix(RUN_LOG_FILE_SUFFIX)
    val parts = middle.split("_")
    if (parts.size != 3) return null
    val (key, startedAt, suffix) = parts
    if (key.length != 16 || key.any { it !in HEX }) return null
    if (suffix.length != 4 || suffix.any { it !in HEX }) return null
    val millis = startedAt.toLongOrNull() ?: return null
    return ParsedRunLogFileName(key = key, startedAtMillis = millis, randomSuffix = suffix)
}

/**
 * 一次历史运行。
 *
 * **没有实例 ID**：死掉的实例，`executionId` 指向一个 IDE 已经不再持有的东西，
 * agent 拿它既不能停也不能查日志（我们的 `stop` / `rerun` 都是按配置名来的）。
 * 所以身份就是 [logFile] —— 一次运行一个文件，天然唯一。
 *
 * @param endedAt 进程退出时刻；sidecar 缺失时为 null
 * @param pid 进程号；sidecar 缺失时为 null
 * @param exitCode 退出码；sidecar 缺失时为 null
 */
internal data class RunLogEntry(
    val startedAtMillis: Long,
    val logFile: String,
    val endedAtMillis: Long? = null,
    val pid: Long? = null,
    val exitCode: Int? = null,
) {
    /** 这次运行持续了多久；sidecar 缺失时算不出来。 */
    val durationMillis: Long? get() = endedAtMillis?.let { it - startedAtMillis }
}

/** 把 epoch 毫秒转成 ISO-8601 字符串（UTC）。给模型看比裸数字友好。 */
internal fun isoTimestamp(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

/**
 * 历史条目的 JSON。
 *
 * 字段全是"能算就算、算不出给 null"：sidecar 缺失时 `endedAt` / `durationMs` / `pid` / `exitCode`
 * 全是 null，但**键保留**（见 `AGENTS.md` 的 null 键约定：agent 要能区分"键不存在"和"值为 null"）。
 */
internal fun RunLogEntry.toJson(): JsonObject = buildJsonObject {
        put("startedAt", startedAtMillis)
        put("startedAtIso", isoTimestamp(startedAtMillis))
        put("endedAt", endedAtMillis)
        put("durationMs", durationMillis)
        put("pid", pid)
        put("exitCode", exitCode)
        put("logFile", logFile)
    }
