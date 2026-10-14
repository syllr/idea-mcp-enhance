package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 四个 MCP 工具的**描述符**（名字、说明、输入 JSON Schema、行为注解）。
 *
 * 全部集中在这里而不是散落在各个工具类里，原因是描述文案本身就是对外契约：
 * agent 靠它决定"该调哪个工具、参数怎么填"。改文案 = 改契约，必须集中且可读。
 */

/** 工具分组。`alwaysIncluded=false` 表示不勾选也会被用户主动选用，不会污染默认工具列表。 */
internal val RUN_LIFECYCLE_CATEGORY = McpToolCategory(
    "Run Lifecycle",
    "com.shenyuanlaolarou.ideamcpenhance.mcp.RunLifecycle",
    false,
    false,
)

// ---------------------------------------------------------------------------
// 输入解析：所有可选参数都必须"没传"和"传了空串"能区分开
// ---------------------------------------------------------------------------

/**
 * 从 JSON 里取一个可选字符串。
 *
 * **不能**用 `?: default` 的写法直接把 null 折叠掉：agent 传 `""`（空串）和不传是两件事，
 * 前者通常表示"显式清空"，后者表示"保持不变"。这里把 null / JsonNull / 非字符串统一当作"没传"。
 */
internal fun JsonObject.optionalString(key: String): String? {
    val element = this[key] ?: return null
    if (element is kotlinx.serialization.json.JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.takeIf { it.isNotEmpty() }
}

/** 取一个可选整数；非整数 / 缺省 / ≤0 一律回落到 [default]。 */
internal fun JsonObject.optionalInt(key: String, default: Int): Int {
    val element = this[key] ?: return default
    if (element is kotlinx.serialization.json.JsonNull) return default
    val primitive = element as? JsonPrimitive ?: return default
    return primitive.content.toIntOrNull()?.takeIf { it > 0 } ?: default
}

/** 取一个可选布尔；只认显式的 `true` / `false`，其余一律回落到 [default]。 */
internal fun JsonObject.optionalBoolean(key: String, default: Boolean): Boolean {
    val element = this[key] ?: return default
    val primitive = element as? JsonPrimitive ?: return default
    return primitive.content.toBooleanStrictOrNull() ?: default
}

/** 取必填字符串；缺失或空白时返回 null，由调用方转成错误结果而不是抛异常。 */
internal fun JsonObject.requiredString(key: String): String? =
    optionalString(key)?.trim()?.takeIf { it.isNotEmpty() }

// ---------------------------------------------------------------------------
// schema 构造小工具
// ---------------------------------------------------------------------------

private fun property(type: String, description: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    put("description", JsonPrimitive(description))
}

private fun properties(vararg entries: Pair<String, JsonObject>): JsonObject =
    JsonObject(entries.toMap())

private fun schema(
    properties: JsonObject,
    required: Set<String> = emptySet(),
): McpToolSchema = McpToolSchema(
    propertiesSchema = properties,
    requiredProperties = required,
    definitions = emptyMap(),
)

private fun annotations(
    readOnly: Boolean,
    destructive: Boolean,
    idempotent: Boolean,
    openWorld: Boolean = false,
) = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = destructive, idempotentHint = idempotent, openWorldHint = openWorld)

// ---------------------------------------------------------------------------
// 1. list_run_configurations_with_state
// ---------------------------------------------------------------------------

internal val LIST_TOOL_NAME = "list_run_configurations_with_state"

internal val listRunConfigurationsDescriptor = McpToolDescriptor(
    LIST_TOOL_NAME,
    "List Run Configurations With State",
    """
    List every run configuration in the current project **together with its live execution state**.

    Why this exists: the IDE's built-in MCP server can start a run configuration but cannot report
    what is currently running. After two calls to `execute_run_configuration` on the same server-type
    configuration you end up with two processes and a port that drifted from 5173 to 5174, while the
    IDE still shows the first tab as "running". This tool answers "what is actually running right now".

    Returns one entry per configuration (templates are marked with `isTemplate: true`), each with:
    - `running` / `runningInstanceCount` — `runningInstanceCount > 1` means the configuration was started more than once
    - `instances[]` — one entry per Run content: `executionId`, `running`, `terminating`, `pid`, `exitCode`
    """.trimIndent(),
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.list_run_configurations_with_state",
    schema(
        properties = properties(
            "includeTemplates" to property(
                "boolean",
                "Whether to also list run configuration templates. Defaults to true; templates are otherwise noise for most callers.",
            ),
        ),
    ),
    null,
    annotations(readOnly = true, destructive = false, idempotent = true),
)

// ---------------------------------------------------------------------------
// 2. get_run_configuration_status
// ---------------------------------------------------------------------------

internal const val STATUS_TOOL_NAME = "get_run_configuration_status"

internal val getRunConfigurationStatusDescriptor = McpToolDescriptor(
    STATUS_TOOL_NAME,
    "Get Run Configuration Status",
    """
    Report the live execution state of a single run configuration.

    Use this before deciding whether to stop or rerun something, and after a start call to confirm
    what actually came up. Unlike `execute_run_configuration`, this tool never starts anything.

    `configurationName` is matched against run configuration names. Matching is exact first, then
    case-insensitive; if that still fails, the error lists the available names.
    """.trimIndent(),
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.get_run_configuration_status",
    schema(
        properties = properties(
            "configurationName" to property(
                "string",
                "Name of the run configuration, as reported by `list_run_configurations_with_state`.",
            ),
        ),
        required = setOf("configurationName"),
    ),
    null,
    annotations(readOnly = true, destructive = false, idempotent = true),
)

// ---------------------------------------------------------------------------
// 3. stop_run_configuration
// ---------------------------------------------------------------------------

internal const val STOP_TOOL_NAME = "stop_run_configuration"

internal val stopRunConfigurationDescriptor = McpToolDescriptor(
    STOP_TOOL_NAME,
    "Stop Run Configuration",
    """
    Stop **all** running instances of a run configuration and wait for them to actually exit.

    Every instance is stopped, not just the newest one. That is the whole point of this tool: the
    built-in `execute_run_configuration` spawns a new process on every call, so a configuration can
    easily have several live instances while the IDE only shows one of them. Stopping just one would
    leave the port still held.

    The stop request goes through the IDE's own `ProcessHandler`, so the Run tool window and the
    process state stay consistent — the opposite of an external `pkill`, which leaves the IDE thinking
    the process is still alive.

    Parameters:
    - `waitTimeoutMs` — how long to wait for the processes to exit. Defaults to 5000.
    - `force` — if the processes are still terminating when the timeout expires, kill them outright.
      Defaults to true. Set false when the process is expected to need a graceful shutdown.
    """.trimIndent(),
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.stop_run_configuration",
    schema(
        properties = properties(
            "configurationName" to property(
                "string",
                "Name of the run configuration whose instances should be stopped.",
            ),
            "waitTimeoutMs" to property(
                "integer",
                "How long to wait for the processes to exit, in milliseconds. Defaults to 5000. Values <= 0 fall back to the default.",
            ),
            "force" to property(
                "boolean",
                "Kill the processes outright if they are still terminating when the timeout expires. Defaults to true.",
            ),
        ),
        required = setOf("configurationName"),
    ),
    null,
    annotations(readOnly = false, destructive = true, idempotent = true),
)

// ---------------------------------------------------------------------------
// 4. rerun_run_configuration
// ---------------------------------------------------------------------------

internal const val RERUN_TOOL_NAME = "rerun_run_configuration"

internal val rerunRunConfigurationDescriptor = McpToolDescriptor(
    RERUN_TOOL_NAME,
    "Rerun Run Configuration",
    """
    Restart a run configuration — exactly what the IDE's **Rerun** button does.

    Semantics:
    - configuration is running → stop the running instances, then start again (`mode: RESTARTED`)
    - configuration is not running → just start it (`mode: STARTED`)
    - something prevents the start → nothing happens, `mode: NO_OP`, and `warnings[]` says why

    This goes through `ExecutionManager.restartRunProfile`, the same code path the Rerun button uses.
    It is language-agnostic (no command-line matching), needs no fixed listening port, and does not
    require changing how the project starts the application — unlike the usual workaround of wrapping
    the start command in a `pkill && start` script, which only ever works for one language.

    Use this after editing source code of a process that has no hot reload.
    """.trimIndent(),
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.rerun_run_configuration",
    schema(
        properties = properties(
            "configurationName" to property(
                "string",
                "Name of the run configuration to rerun.",
            ),
            "stopTimeoutMs" to property(
                "integer",
                "How long to wait for the old instances to exit before starting again, in milliseconds. Defaults to 5000.",
            ),
        ),
        required = setOf("configurationName"),
    ),
    null,
    annotations(readOnly = false, destructive = true, idempotent = false),
)

/** 默认等待窗口。给 MCP 的调用设一个上限，避免 agent 卡在一个永不退出的进程上。 */
internal const val DEFAULT_WAIT_TIMEOUT_MS = 5_000

internal fun defaultWaitTimeoutMs(): Int = DEFAULT_WAIT_TIMEOUT_MS

// ---------------------------------------------------------------------------
// 供测试使用：把 JsonObject 里的某个键读回字符串的小工具
// ---------------------------------------------------------------------------

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject