package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 命名文法的解析：`[os@]base[@env]`。
 *
 * 平台对配置名没有任何结构理解，但我们的约定有（见 opencode-idea 侧的
 * `idea-run-config-manager` skill）：
 *
 * | 段    | 位置 | 取值   | 缺省          |
 * | ----- | ---- | ------ | ------------- |
 * | `os`  | 最前 | `win`  | macOS / Linux |
 * | `base`| 中间 | 配置本体 | 必填        |
 * | `env` | 最后 | 环境名 | 所有环境通用  |
 *
 * 例子：`console-web`（mac/Linux、通用环境）、`win@console-web`（Windows）、
 * `console-web@testenv`（某个环境）、`win@console-web@yutairstandalone`（Windows + 某个环境）。
 *
 * 解析规则是**从两头往中间收**，所以永远不会有歧义：
 *
 * - 末段是**环境**（只有两段及以上才有）
 * - 首段是**平台** —— 但只有恰好是 `win` 才算；不是 `win` 的首段并进 base
 * - 中间剩下的是 **base**
 *
 * 这样 `a@b@c` 解析成 base=`a@b`、env=`c`，不会把哪一段丢掉。
 *
 * 解析是纯函数：它把"这个配置是哪个 base 的哪个变体"变成工具给出的**事实**，
 * 而不是让 agent 自己去猜字符串。
 */

/** 平台段：Windows。 */
internal const val PLATFORM_WINDOWS = "win"

/** 平台段：macOS / Linux（缺省）。 */
internal const val PLATFORM_MAC_LINUX = "mac-linux"

/** 解析结果。 */
internal data class RunConfigurationNaming(
    /** 平台：`win` 或 `mac-linux`（缺省）。 */
    val platform: String,
    /** 配置本体 —— 去掉平台段与环境段之后剩下的名字。 */
    val base: String,
    /** 环境名；没有就是 null（= 所有环境通用这一套）。 */
    val env: String?,
)

/** 解析配置名。空名没有意义，返回 null。 */
internal fun parseRunConfigurationName(name: String): RunConfigurationNaming? {
    if (name.isBlank()) return null
    val parts = name.split("@")
    val platform = if (parts.size >= 2 && parts.first() == PLATFORM_WINDOWS) {
        PLATFORM_WINDOWS
    } else {
        PLATFORM_MAC_LINUX
    }
    // 环境段只存在于"末段不是 base 本身"的形状里：
    // - `win@base` 两段 = 平台 + base，没有环境
    // - `base@env` 两段 = base + 环境
    // - 三段及以上 = 首段（若为 win）+ 中间 base + 末段环境
    val hasEnv = parts.size >= 3 || (parts.size == 2 && platform != PLATFORM_WINDOWS)
    val env = if (hasEnv) parts.last() else null
    val base = when {
        parts.size == 1 -> parts[0]
        platform == PLATFORM_WINDOWS ->
            parts.subList(1, if (hasEnv) parts.size - 1 else parts.size).joinToString("@")
        else -> parts.subList(0, parts.size - 1).joinToString("@")
    }
    if (base.isEmpty()) return null
    return RunConfigurationNaming(platform = platform, base = base, env = env)
}

/** 命名解析结果的 JSON。 */
internal fun RunConfigurationNaming.toJson(): JsonObject = buildJsonObject {
    put("platform", platform)
    put("base", base)
    put("env", env)
}
