package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 三个 MCP 工具的**描述符**（名字、说明、输入 JSON Schema、输出 JSON Schema、行为注解）。
 *
 * 全部集中在这里而不是散落在各个工具类里，原因是描述文案本身就是对外契约：
 * agent 靠它决定"该调哪个工具、参数怎么填"，也靠 `outputSchema` 校验返回结构。
 * 改文案 / 改结构 = 改契约，必须集中且可读。
 *
 * 与内置 MCP Server 的对齐是硬要求，因此这里有两处是**逐字照抄**官方约定的：
 * [PROJECT_PATH_DESCRIPTION] 与"根级 `type: object` + `required` 数组"的 schema 形状。
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

// 下面这些构 schema 的小工具是**两套工具共用**的（Run Lifecycle 与 Deployment），
// 所以是 internal 而不是 private：DeploymentSchemas 与 RunLifecycleSchemas 同包，
// 共用一份可以避免两边各写一套、日后悄悄漂移。

internal fun property(type: String, description: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    put("description", JsonPrimitive(description))
}

/**
 * 可空属性，写成官方那种 `"type": ["integer", "null"]`。
 *
 * 输出 schema 里凡是有可能出现 null 的字段都用它——直接写 `"integer"` 会让严格客户端
 * 在一个正常返回（例如进程还在跑、`exitCode` 为 null）上校验失败。
 */
internal fun nullableProperty(type: String, description: String): JsonObject = buildJsonObject {
    put("type", buildJsonArray {
        add(JsonPrimitive(type))
        add(JsonPrimitive("null"))
    })
    put("description", JsonPrimitive(description))
}

internal fun arrayOf(item: JsonObject, description: String? = null): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("array"))
    if (description != null) put("description", JsonPrimitive(description))
    put("items", item)
}

internal fun properties(vararg entries: Pair<String, JsonObject>): JsonObject =
    JsonObject(entries.toMap())

internal fun schema(
    properties: JsonObject,
    required: Set<String> = emptySet(),
): McpToolSchema = McpToolSchema(
    propertiesSchema = properties,
    requiredProperties = required,
    definitions = emptyMap(),
)

internal fun annotations(
    readOnly: Boolean,
    destructive: Boolean,
    idempotent: Boolean,
    openWorld: Boolean = false,
) = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = destructive, idempotentHint = idempotent, openWorldHint = openWorld)

/**
 * `projectPath` 参数，**逐字照抄**内置 MCP Server 的描述。
 *
 * 为什么要跟它一模一样：MCP Server 在派发任何工具之前会先解析"这次调用针对哪个项目"，
 * 解析依据是 **header `IJ_MCP_SERVER_PROJECT_PATH` 或调用参数里的 `projectPath`**。
 * 客户端配了 header 时它自己就解决了；没配 header 的客户端只能靠这个参数，
 * 而 agent 只能从 schema 里知道有这个参数——所以必须声明，且必须和它认知的写法一致。
 *
 * schema 层面它是**可选**的（官方 58 个工具里有 54 个声明了它，但都不放进 `required`）。
 */
internal const val PROJECT_PATH_DESCRIPTION =
    " The project path. Pass this value ALWAYS if you are aware of it. It reduces numbers of ambiguous calls. \n" +
        " In the case you know only the current working directory you can use it as the project path.\n" +
        " If you're not aware about the project path you can ask user about it."

internal fun projectPathProperty(): JsonObject = property("string", PROJECT_PATH_DESCRIPTION)

// ---------------------------------------------------------------------------
// 输出 schema 的公共片段（多处复用，避免三份 instances[] 定义漂移）
// ---------------------------------------------------------------------------

/** 一个运行实例的状态，对应 `RunInstanceState.toJson()`。 */
private val RUN_INSTANCE_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One Run content (one execution) of this configuration."))
    put("properties", properties(
        "executionId" to property("integer", "IDE-assigned execution id of this Run content."),
        "displayName" to property("string", "Label of the Run tool window tab; usually the configuration name plus an index."),
        "running" to property("boolean", "The process has not terminated yet."),
        "terminating" to property("boolean", "Termination was requested but the process has not exited yet."),
        "startNotified" to property("boolean", "The IDE already observed the process start. false while still running build steps."),
        "pid" to nullableProperty("integer", "Operating system process id; null when the process does not exist (yet)."),
        "exitCode" to nullableProperty("integer", "Process exit code; null while it is still running."),
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("executionId"))
        add(JsonPrimitive("displayName"))
        add(JsonPrimitive("running"))
        add(JsonPrimitive("terminating"))
        add(JsonPrimitive("startNotified"))
        add(JsonPrimitive("pid"))
        add(JsonPrimitive("exitCode"))
    })
}

/**
 * 列表工具用的**精简**实例形状。
 *
 * 刻意比 [RUN_INSTANCE_SCHEMA] 少几个字段：`isTemplate` / `running` / `exitCode` 在"只列活实例"
 * 的前提下全是常量，`displayName` 是不可信的 UI 文本；`terminating` + `startNotified` 两个布尔
 * 合并成一个 [INSTANCE_STATE_RUNNING] 之类的三态。
 */
private val LIST_INSTANCE_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One live instance (one execution) of this configuration."))
    put("properties", properties(
        "executionId" to property("integer", "IDE-assigned execution id of this Run content."),
        "state" to property(
            "string",
            "`running` (the process is alive), `terminating` (a stop was requested but the process has not exited yet) " +
                "or `starting` (the process is not up yet — still running before-launch steps).",
        ),
        "pid" to nullableProperty("integer", "Operating system process id; null when the process does not exist (yet)."),
        "logFile" to nullableProperty(
            "string",
            "Path of the log file this instance writes to; null when no log could be captured for it.",
        ),
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("executionId"))
        add(JsonPrimitive("state"))
        add(JsonPrimitive("pid"))
        add(JsonPrimitive("logFile"))
    })
}

/**
 * 「最近一次运行」的 schema。
 *
 * 四个工具共用：`list` / `rerun` / `deploy` 的 `beforeRunTasks[].lastRun`，以及 `inspect` 的
 * `beforeRunTasks[].lastRun`。七个键即使从没跑过也保留、值为 null。
 */
internal val BEFORE_RUN_LAST_RUN_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive(
        "The step's most recent run; every field is null when it has never run.",
    ))
    put("properties", properties(
        "startedAt" to nullableProperty("integer", "When that run started, in epoch milliseconds."),
        "startedAtIso" to nullableProperty("string", "When that run started, in ISO-8601."),
        "endedAt" to nullableProperty("integer", "When it exited, in epoch milliseconds."),
        "durationMs" to nullableProperty("integer", "How long it ran, in milliseconds."),
        "pid" to nullableProperty("integer", "Operating system process id."),
        "exitCode" to nullableProperty("integer", "Exit code; null while it is still running."),
        "logFile" to nullableProperty("string", "The log file that run produced."),
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("startedAt"))
        add(JsonPrimitive("startedAtIso"))
        add(JsonPrimitive("endedAt"))
        add(JsonPrimitive("durationMs"))
        add(JsonPrimitive("pid"))
        add(JsonPrimitive("exitCode"))
        add(JsonPrimitive("logFile"))
    })
}

/**
 * 一条 Before launch 步骤 + 它最近一次运行。
 *
 * `list_running_configurations` 与 `rerun_run_configuration` 共用：两处都在回答
 * 「构建到底跑了没」，形状必须一致。
 */
internal val BEFORE_RUN_STEP_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive(
        "A before-launch step, with its most recent run.",
    ))
    put("properties", properties(
        "name" to property("string", "The configuration this step launches."),
        "enabled" to property("boolean", "false when the step is switched off in the IDE."),
        "output" to nullableProperty(
            "string",
            "Tail preview of the step's most recent log (at most 10000 characters), prefixed with " +
                "`<truncated at the start>` when cut. null when there is no log to read, or when the " +
                "tool does not read log content (the list tool leaves it null and gives `logFile`).",
        ),
        "lastRun" to BEFORE_RUN_LAST_RUN_SCHEMA,
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("name"))
        add(JsonPrimitive("enabled"))
        add(JsonPrimitive("output"))
        add(JsonPrimitive("lastRun"))
    })
}

/** 列表工具用的配置级形状：名字、类型、活着的实例。 */
private val LIST_CONFIGURATION_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One run configuration together with its live instances and its before-launch links."))
    put("properties", properties(
        "name" to property("string", "Run configuration name."),
        "type" to property("string", "Run configuration type id, e.g. ShConfigurationType or MavenRunConfiguration."),
        "instances" to arrayOf(LIST_INSTANCE_SCHEMA, "Live instances of this configuration."),
        "beforeRunTasks" to arrayOf(
            BEFORE_RUN_STEP_SCHEMA,
            "Before-launch steps of this configuration; entry 0 runs first. Empty when it has none.",
        ),
        "beforeLaunchOf" to arrayOf(
            property("string", "Name of a run configuration."),
            "Configurations that launch this one as a before-launch step. Non-empty means this " +
                "configuration is currently running **on behalf of** those; empty means it is a " +
                "top-level run.",
        ),
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("name"))
        add(JsonPrimitive("type"))
        add(JsonPrimitive("instances"))
        add(JsonPrimitive("beforeRunTasks"))
        add(JsonPrimitive("beforeLaunchOf"))
    })
}

/** rerun 状态流转的一个节点，对应 `RunTransitionStep.toJson()`。 */
private val RUN_TRANSITION_STEP_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One observed step of the restart, in chronological order."))
    put("properties", properties(
        "phase" to property("string", "STOPPED, STARTED, TRIGGERED, START_SUBMITTED or START_FAILED."),
        "detail" to property("string", "Human-readable detail for this step, e.g. instance count and exit codes."),
        "elapsedMs" to property("integer", "Milliseconds from issuing the rerun request to reaching this step."),
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("phase"))
        add(JsonPrimitive("detail"))
        add(JsonPrimitive("elapsedMs"))
    })
}

internal val WARNINGS_SCHEMA: JsonObject =
    arrayOf(property("string", "Warning message."), "Non-fatal problems encountered during the call.")

private val AVAILABLE_NAMES_SCHEMA: JsonObject =
    arrayOf(property("string", "Name of a run configuration that does exist."), "Candidate configuration names, sorted.")

// ---------------------------------------------------------------------------
// 1. list_running_configurations
// ---------------------------------------------------------------------------

internal val LIST_TOOL_NAME = "list_running_configurations"

internal val listRunConfigurationsDescriptor = McpToolDescriptor(
    LIST_TOOL_NAME,
    "List Running Configurations",
    """
    Report what is running right now in the IDE: one entry per configuration that has a live Run
    session, with its live instances (pid, state, `logFile`).

    This lists **live sessions only**. That matters for before-launch steps: a step runs as its own
    run configuration, usually finishes in seconds, and disappears from this list the moment it
    ends — so an empty result, or a list without the build, does **not** mean before-launch did not
    run. Read `beforeRunTasks[].lastRun` here for whether it ran and how it ended, or
    `inspect_run_configuration` for its full history.

    Each entry also carries `beforeLaunchOf`: when it is non-empty, this configuration is currently
    running **on behalf of** those configurations (it is their before-launch step), not as a
    top-level run.

    This is an index of live sessions, not of definitions — `get_run_configurations` lists
    definitions. Templates never appear; several instances under one configuration mean it was
    started more than once.

    A `running: true` instance means the Run session exists, **not** that the service has finished
    starting. Right after `rerun_run_configuration` on a `server` the launch (and, for a `Remote
    Process`, its deployment sync) is still in flight — poll with a growing backoff (2s, 4s, 8s, 16s)
    instead of looping.

    For a `Remote Process` configuration the pid is the **remote** process id, and `logFile` is
    the local aggregated log of the session (progress plus the remote stdout/stderr) — not a file on
    the remote host. To know whether a configured remote process is running right now, including
    ones started outside the IDE, use `list_remote_processes`.

    Names match exactly first, then case-insensitively.
    """.trimIndent(),
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.list_running_configurations",
    schema(
        properties = properties(
            "projectPath" to projectPathProperty(),
        ),
    ),
    schema(
        properties = properties(
            "configurations" to arrayOf(
                LIST_CONFIGURATION_SCHEMA,
                "One entry per configuration that currently has at least one live process; its `instances` are the live ones only.",
            ),
        ),
        required = setOf("configurations"),
    ),
    annotations(readOnly = true, destructive = false, idempotent = true),
)

// ---------------------------------------------------------------------------
// 2. stop_run_configuration
// ---------------------------------------------------------------------------

internal const val STOP_TOOL_NAME = "stop_run_configuration"

internal val stopRunConfigurationDescriptor = McpToolDescriptor(
    STOP_TOOL_NAME,
    "Stop Run Configuration",
    """
    Stop every running instance of a configuration and wait for it to exit.

    All instances are stopped, not just the newest, through the IDE's `ProcessHandler`; the Run
    window stays consistent (unlike `pkill`). `force` (default true) kills whatever is still
    terminating when the wait window expires.

    For a deploy-only `Remote Process` session — the one `deploy_remote_process` opened — stopping
    closes the log follower and leaves the remote process / container running. Stopping a plain
    `Remote Process` run session does kill the remote one.

    Idempotent: not running → success with `matchedInstances: 0`. If a start is in flight the tool
    waits briefly; if the process still does not appear it returns success plus a warning — confirm
    with `list_running_configurations`.

    Names match exactly first, then case-insensitively; errors list the existing names.
    """.trimIndent(),
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.stop_run_configuration",
    schema(
        properties = properties(
            "configurationName" to property(
                "string",
                "Name of the run configuration whose instances should be stopped.",
            ),
            "force" to property(
                "boolean",
                "Kill the processes outright if they are still terminating when the timeout expires. Defaults to true.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("configurationName"),
    ),
    schema(
        properties = properties(
            "configurationName" to property("string", "Resolved run configuration name (after exact / case-insensitive matching)."),
            "matchedInstances" to property("integer", "Number of Run contents that matched this configuration name."),
            "requestedStops" to property("integer", "Number of instances a stop request was actually sent to."),
            "forcedKills" to property("integer", "Number of instances that had to be killed after the wait timed out."),
            "timedOut" to property("boolean", "true when instances were still running after the wait window."),
            "allTerminated" to property("boolean", "true when no instance of this configuration is running any more."),
            "instances" to arrayOf(
                RUN_INSTANCE_SCHEMA,
                "State of every matched instance after the stop attempt; finished instances stay in the list " +
                    "(their exit codes show how they ended).",
            ),
            "warnings" to WARNINGS_SCHEMA,
        ),
        required = setOf(
            "configurationName",
            "matchedInstances",
            "requestedStops",
            "forcedKills",
            "timedOut",
            "allTerminated",
            "instances",
            "warnings",
        ),
    ),
    annotations(readOnly = false, destructive = true, idempotent = true),
)

// ---------------------------------------------------------------------------
// 3. rerun_run_configuration
// ---------------------------------------------------------------------------

internal const val RERUN_TOOL_NAME = "rerun_run_configuration"

internal val rerunRunConfigurationDescriptor = McpToolDescriptor(
    RERUN_TOOL_NAME,
    "Rerun Run Configuration",
    """
    Restart a run configuration, like the IDE's Rerun button.

    Running → stop then start (`RESTARTED`); not running → start (`STARTED`). Read `triggered` and
    `started`, not `mode`: `started: false` carries `startFailure`; `triggered: false,
    started: null` means the request never reached the platform; `triggered: true, started: null`
    is the normal `server` outcome (see below). It fails fast if the configuration is stopping or
    starting, or old instances cannot be stopped.

    `runKind` says what is waited for. A `job` (a build script, a test, a migration — the archive calls
    it `build`) is waited until the process exits, and then `exitCode` and the full output are
    returned. A `server` (a dev server, a remote process) returns as soon as the start is
    **triggered**, not when the service is up: for a `Remote Process` the deployment sync and the SSH
    launch run in the background and can take tens of seconds. `triggered: true` with `started: null`
    states exactly that. The kind comes
    from the configuration's own environment variable `IDEA_MCP_ENHANCE_SERVER_RUN_CONFIG`: setting it
    (to anything but `false` / `0` / `no` / `off`) means server, leaving it out means job. A
    `Remote Process` configuration is always a server.

    **Do not block on a server.** Read `fullOutputPath` for the startup log, and poll
    `list_running_configurations` with a growing backoff — 2s, then 4s, 8s, 16s. A `running: true`
    entry only means the Run session exists; it does not prove the service is serving.

    For a `Remote Process` configuration this starts the remote process / container over SSH, and
    stopping it later kills the remote one. Use `deploy_remote_process` when the goal is to publish:
    its Stop only closes the log follower and leaves the remote running. The project needs only one
    rule — **publish with the remote `{app}@{env}` configuration (`deploy_remote_process`); debug or
    restart with the application's own configuration (this tool)**. The configuration type decides,
    not a per-call judgement.

    **Before-launch steps run first**, each as its own Run session with its own log file. Their
    output is never part of this call's `output` / `fullOutputPath` — those cover this configuration
    only. `beforeRunTasks[]` in the result carries each step's most recent run, including `exitCode`
    and `logFile`, so a build that already finished is visible without calling anything else. When a
    step never ran, `lastRun` is null.

    It always uses the configuration as it is stored. To change arguments, the working directory or
    environment variables, edit the configuration in the IDE — there is no per-run override, so what
    ran stays visible in `.run/`.

    This always starts a process. To only copy files to the remote host, use `remote_upload` /
    `remote_download` instead.

    Names match exactly first, then case-insensitively; errors list existing names.
    """.trimIndent(),
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.rerun_run_configuration",
    schema(
        properties = properties(
            "configurationName" to property(
                "string",
                "Name of the run configuration to rerun.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("configurationName"),
    ),
    schema(
        properties = properties(
            "configurationName" to property("string", "Resolved run configuration name."),
            "mode" to property("string", "What was attempted: RESTARTED, STARTED or NO_OP."),
            "triggered" to property(
                "boolean",
                "Whether the platform accepted the start request (a Run session exists). For a `server` this is " +
                    "the normal success signal: `triggered: true` with `started: null` means the start was " +
                    "triggered, not confirmed up.",
            ),
            "started" to nullableProperty(
                "boolean",
                "Whether a new process really came up. Read this, not `mode`. `true`/`false` when the start was " +
                    "awaited; `null` for a `server` (only `triggered` is confirmed) or when the wait window ran " +
                    "out — then the outcome is unknown and `list_running_configurations` has the real state.",
            ),
            "startFailure" to nullableProperty(
                "string",
                "Why the start failed; null when it did not fail — including a `server`, whose success is not " +
                    "confirmed either way (`triggered: true`, `started: null`).",
            ),
            "stoppedInstances" to property("integer", "Number of old instances a stop request was sent to."),
            "runningAfter" to nullableProperty(
                "integer",
                "Live instance count after the new process was confirmed up. `null` when the start was only " +
                    "triggered (a `server`), was not awaited, or the wait window ran out — never 0 as a stand-in " +
                    "for \"unknown\".",
            ),
            "sessionId" to nullableProperty(
                "string",
                "Run session identifier of the new process: the configuration name, or `<name>#<executionId>` " +
                    "when several live instances share that name.",
            ),
            "runKind" to property(
                "string",
                "What was waited for: `job` (waited until the process exited) or `server` (returned once the " +
                    "start was triggered).",
            ),
            "exited" to nullableProperty(
                "boolean",
                "For a `job`: whether the process really exited within the wait window. `false` means it was " +
                    "still running, so `exitCode` is unknown. `null` for a `server`, or when the start was not awaited.",
            ),
            "exitCode" to nullableProperty(
                "integer",
                "For a `job` that exited: its exit code. null when it did not exit within the wait window, or " +
                    "for a `server`.",
            ),
            "output" to property("string", "Inline preview of the new process output, at most 10000 characters."),
            "fullOutputPath" to nullableProperty("string", "Path of the full raw output log; it keeps growing while the process is alive."),
            "transition" to arrayOf(RUN_TRANSITION_STEP_SCHEMA, "Steps of this restart, in chronological order."),
            "beforeRunTasks" to arrayOf(
                BEFORE_RUN_STEP_SCHEMA,
                "Before-launch steps of this configuration with their most recent run. Empty when it " +
                    "has none. Each entry carries `output` (a tail preview of that step's own log, at most " +
                    "10000 characters) plus `lastRun` — a before-launch build runs as its own Run session, so " +
                    "its log is not in `fullOutputPath`.",
            ),
            "instances" to arrayOf(
                RUN_INSTANCE_SCHEMA,
                "All Run contents of this configuration after the restart, finished ones included.",
            ),
            "warnings" to WARNINGS_SCHEMA,
        ),
        required = setOf(
            "configurationName",
            "mode",
            "triggered",
            "started",
            "stoppedInstances",
            "runningAfter",
            "runKind",
            "exited",
            "exitCode",
            "beforeRunTasks",
            "output",
            "transition",
            "instances",
            "warnings",
        ),
    ),
    annotations(readOnly = false, destructive = true, idempotent = false),
)

/**
 * 停止 / 重启时等进程退出的默认预算。
 *
 * 与其他工具统一为 **300 秒**：5 秒对真实的优雅退出（JVM 关停钩子、连接池收尾）太短，
 * 会把正常退出误报成"还在跑"。等不起的调用方改用异步提交 + 自己轮询。
 */
internal const val DEFAULT_WAIT_TIMEOUT_MS = 300_000

/**
 * 等新进程被创建的上限。
 *
 * 同样是 **300 秒**：before-launch（构建 / 另一个配置）经常远超过 10 秒，旧的 10 秒默认会把
 * 正常的编译等待误报成"启动失败"。整次调用的时长会被调用方一直等着 —— 等不起就用异步模式。
 */
internal const val DEFAULT_START_TIMEOUT_MS = 300_000

internal fun defaultWaitTimeoutMs(): Int = DEFAULT_WAIT_TIMEOUT_MS

internal fun defaultStartTimeoutMs(): Int = DEFAULT_START_TIMEOUT_MS

// ---------------------------------------------------------------------------
// 供测试使用：把 JsonObject 里的某个键读回字符串的小工具
// ---------------------------------------------------------------------------

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

