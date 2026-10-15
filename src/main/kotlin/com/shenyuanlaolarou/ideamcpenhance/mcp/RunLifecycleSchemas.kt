package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

private fun property(type: String, description: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(type))
    put("description", JsonPrimitive(description))
}

/**
 * 可空属性，写成官方那种 `"type": ["integer", "null"]`。
 *
 * 输出 schema 里凡是有可能出现 null 的字段都用它——直接写 `"integer"` 会让严格客户端
 * 在一个正常返回（例如进程还在跑、`exitCode` 为 null）上校验失败。
 */
private fun nullableProperty(type: String, description: String): JsonObject = buildJsonObject {
    put("type", buildJsonArray {
        add(JsonPrimitive(type))
        add(JsonPrimitive("null"))
    })
    put("description", JsonPrimitive(description))
}

private fun arrayOf(item: JsonObject, description: String? = null): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("array"))
    if (description != null) put("description", JsonPrimitive(description))
    put("items", item)
}

private fun objectProperty(type: String, description: String) = property(type, description)

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
private const val PROJECT_PATH_DESCRIPTION =
    " The project path. Pass this value ALWAYS if you are aware of it. It reduces numbers of ambiguous calls. \n" +
        " In the case you know only the current working directory you can use it as the project path.\n" +
        " If you're not aware about the project path you can ask user about it."

private fun projectPathProperty(): JsonObject = property("string", PROJECT_PATH_DESCRIPTION)

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

/** 一个 Run Configuration 及其全部实例，对应 `RunConfigurationState.toJson()`。 */
private val RUN_CONFIGURATION_STATE_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One run configuration together with its current Run contents."))
    put("properties", properties(
        "name" to property("string", "Run configuration name."),
        "type" to property("string", "Run configuration type id, e.g. ShConfigurationType or MavenRunConfiguration."),
        "isTemplate" to property("boolean", "true for run configuration templates."),
        "running" to property("boolean", "At least one instance is live."),
        "runningInstanceCount" to property("integer", "Number of live instances. Greater than 1 means the configuration was started more than once."),
        "instances" to arrayOf(RUN_INSTANCE_SCHEMA, "Run contents of this configuration."),
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("name"))
        add(JsonPrimitive("type"))
        add(JsonPrimitive("isTemplate"))
        add(JsonPrimitive("running"))
        add(JsonPrimitive("runningInstanceCount"))
        add(JsonPrimitive("instances"))
    })
}

/** rerun 状态流转的一个节点，对应 `RunTransitionStep.toJson()`。 */
private val RUN_TRANSITION_STEP_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One observed step of the restart, in chronological order."))
    put("properties", properties(
        "phase" to property("string", "STOPPED, STARTED, START_SUBMITTED or START_FAILED."),
        "detail" to property("string", "Human-readable detail for this step, e.g. instance count and exit codes."),
        "elapsedMs" to property("integer", "Milliseconds from issuing the rerun request to reaching this step."),
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("phase"))
        add(JsonPrimitive("detail"))
        add(JsonPrimitive("elapsedMs"))
    })
}

private val WARNINGS_SCHEMA: JsonObject =
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
    Report what is **actually running right now** in the current project.

    Why this exists: the built-in `get_run_configurations` tells you which configurations exist but
    says nothing about execution. After two calls to `execute_run_configuration` on the same
    server-type configuration you end up with two processes and a port that drifted from 5173 to
    5174, while the IDE still shows only the first tab as "running". This tool answers the question
    the IDE's Run tool window answers: which configurations have live processes, how many each, and
    with which process id.

    Everything in the result is live. Configurations with no running process are not returned at all
    — that already covers run configuration templates, since a template is not an instance and can
    never be running, so there is nothing to filter. Finished Run contents are left out of
    `instances[]` as well. `runningInstanceCount` is therefore simply how many processes the
    configuration currently has; greater than 1 means it was started more than once, which is exactly
    how the port-drifting problem shows up.

    Use this before `stop_run_configuration` / `rerun_run_configuration` to see what is up, and after
    them to confirm the result.
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
            "runningCount" to property("integer", "Number of configurations that currently have at least one live process."),
            "configurations" to arrayOf(
                RUN_CONFIGURATION_STATE_SCHEMA,
                "One entry per configuration that currently has at least one live process; its `instances` are the live ones only.",
            ),
        ),
        required = setOf("runningCount", "configurations"),
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
    Stop **all** running instances of a run configuration and wait for them to actually exit.

    Every instance is stopped, not just the newest one. That is the whole point of this tool: the
    built-in `execute_run_configuration` spawns a new process on every call, so a configuration can
    easily have several live instances while the IDE only shows one of them. Stopping just one would
    leave the port still held.

    The stop request goes through the IDE's own `ProcessHandler`, so the Run tool window and the
    process state stay consistent — the opposite of an external `pkill`, which leaves the IDE thinking
    the process is still alive.

    It is idempotent: stopping a configuration that is not running succeeds with nothing to do
    (`matchedInstances: 0`, `isError: false`). Calling it again to make sure something is down never
    turns into an error.

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
            "instances" to arrayOf(RUN_INSTANCE_SCHEMA, "State of every matched instance after the stop attempt."),
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
    Restart a run configuration — the same observable effect as the IDE's **Rerun** button.

    Semantics:
    - configuration is running → stop the running instances, then start again (`mode: RESTARTED`)
    - configuration is not running → just start it (`mode: STARTED`)
    - the IDE cannot launch this configuration type → nothing happens, `mode: NO_OP`

    The call is **synchronous with respect to the start**: a new process is launched and the tool waits
    until it has really been created (then collects its output for a short window) before returning. So
    read `started` — not `mode` — to know whether a new process is up. `started: false` comes with
    `startFailure`. `transition[]` reports every step of the restart in order: the old instances that
    were stopped (with their exit codes) and then the new process, each with the elapsed time.

    Output handling mirrors the built-in `execute_run_configuration`:
    - `output` — inline preview of the new process output, up to 10000 characters, `<truncated>` beyond that
    - `fullOutputPath` — a log file that keeps growing while the process is alive
    - `sessionId` — run session identifier, or `<name>#<executionId>` when several live instances share a name

    It is language-agnostic (no command-line matching), needs no fixed listening port, and does not
    require changing how the project starts the application — unlike the usual workaround of wrapping
    the start command in a `pkill && start` script, which only ever works for one language.

    Re-entrancy mirrors the IDE's Rerun button. The call fails fast with a structured error when a restart
    does not make sense right now:
    - the configuration is **stopping**, or **starting** (a run was requested but its process is not up yet)
    - its previous instances could not be stopped — then nothing new is started, because that is exactly the
      state that would leave two instances of the same configuration running
    - the IDE itself is already starting this configuration (its own gate, `isStarting(environment)`) — a
      second start submitted inside that window is silently dropped by the IDE, so this call reports it
      instead of being ignored

    All three checks read the IDE's own state, so they cover starts triggered by hand in the IDE or by the
    built-in `execute_run_configuration` just as well. What the third one is really about:
    `ProgramRunnerUtil.executeConfigurationAsync` starts with `if (!manager.isStarting(environment))`, so a
    start submitted while the same configuration/executor/runner is already starting is **silently dropped** —
    no success callback and no failure callback. The IDE never runs two processes, so the configuration stays
    consistent either way; this check only turns that silent drop into an immediate, explicit error.

    Use this after editing source code of a process that has no hot reload.

    By default this call is synchronous: it waits until the new process is created (and collects a short
    output window), so `started` is a real answer. If the configuration has a before-launch step that
    takes longer than the MCP server's patience (roughly 15 seconds), pass `waitForStart = false`: the
    start is then submitted and the call returns immediately with `started = null`, leaving the real
    state to `list_running_configurations`.
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
            "startTimeoutMs" to property(
                "integer",
                "How long to wait for the new process to be created, in milliseconds. Defaults to 10000 — the same " +
                    "default as the built-in `execute_run_configuration`'s `timeout`. Raise it when this configuration " +
                    "has before-launch steps that take a while (a build, or another run configuration): the IDE creates " +
                    "the Run content only after they finish. Keep the whole call short though — the MCP server drops a " +
                    "session when a request stays unanswered for roughly 15 seconds, and then the caller gets an empty " +
                    "HTTP 404 instead of this tool's answer. For a before-launch step that takes longer than that, use " +
                    "`waitForStart = false` instead of a huge value here.",
            ),
            "waitForStart" to property(
                "boolean",
                "Defaults to true: submit the start and wait until the new process is created, then report " +
                    "`started` / `output`. Set it to false when a before-launch step (a build, or another run " +
                    "configuration) takes longer than the MCP server's patience: the start is submitted and this tool " +
                    "returns right away with `started = null` (unknown) and a `START_SUBMITTED` transition step; " +
                    "poll `list_running_configurations` for the real state. Mirrors the built-in " +
                    "`execute_run_configuration`'s `waitForExit` switch.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("configurationName"),
    ),
    schema(
        properties = properties(
            "configurationName" to property("string", "Resolved run configuration name."),
            "mode" to property("string", "What was attempted: RESTARTED, STARTED or NO_OP."),
            "started" to nullableProperty(
                "boolean",
                "Whether a new process really came up. Read this, not `mode`. `true`/`false` when the start was " +
                    "awaited; `null` when `waitForStart = false` was used — then the outcome is unknown and " +
                    "`list_running_configurations` has the real state.",
            ),
            "startFailure" to nullableProperty("string", "Why the new process did not come up; null when `started` is true."),
            "stoppedInstances" to property("integer", "Number of old instances a stop request was sent to."),
            "runningAfter" to nullableProperty(
                "integer",
                "Live instance count after the new process was up and registered; null when `waitForStart = false` " +
                    "(not awaited), not 0.",
            ),
            "sessionId" to nullableProperty("string", "Run session identifier of the new process."),
            "output" to property("string", "Inline preview of the new process output, at most 10000 characters."),
            "fullOutputPath" to nullableProperty("string", "Path of the full raw output log; it keeps growing while the process is alive."),
            "transition" to arrayOf(RUN_TRANSITION_STEP_SCHEMA, "Steps of this restart, in chronological order."),
            "instances" to arrayOf(RUN_INSTANCE_SCHEMA, "All Run contents of this configuration after the restart."),
            "warnings" to WARNINGS_SCHEMA,
        ),
        required = setOf(
            "configurationName",
            "mode",
            "started",
            "stoppedInstances",
            "runningAfter",
            "output",
            "transition",
            "instances",
            "warnings",
        ),
    ),
    annotations(readOnly = false, destructive = true, idempotent = false),
)

// ---------------------------------------------------------------------------
// 4. create_shell_run_configuration
// ---------------------------------------------------------------------------

internal val CREATE_TOOL_NAME = "create_shell_run_configuration"

internal val createRunConfigurationDescriptor = McpToolDescriptor(
    CREATE_TOOL_NAME,
    "Create Shell Run Configuration",
    """
    Create a new **Shell Script** run configuration as a **project-level** run configuration, i.e. stored as a
    `.run/<name>.run.xml` file inside the project (the same place the IDE's "Store as project file"
    checkbox writes to, so it is shared through VCS and visible to the whole team).

    Why this exists: a Shell run configuration carries the project's environment (working directory,
    environment variables, interpreter), so an agent that needs to run project commands with that
    environment creates one with this tool and then starts it with `rerun_run_configuration`.

    Only Shell Script configurations can be created — there is no `type` parameter. Give exactly one of:

    - `scriptPath` — run a script file (e.g. `scripts/dev.sh`, relative to the project root or absolute).
      The file should exist, otherwise the result reports `canRun = false` with the reason.
    - `scriptText` — run an inline script instead of a file.
    - `cloneFrom` — duplicate an existing Shell configuration (its script, options, environment and
      before-launch steps are all copied, so the new one is immediately runnable). The source must
      itself be a Shell configuration.

    Optional shell details: `scriptOptions` (arguments), `workingDirectory` (defaults to the
    template default when omitted), `interpreterPath` (e.g. `/bin/zsh`; omitted = template default),
    `interpreterOptions`, `envs` (extra environment variables as a JSON object; merged over the
    environment the script inherits from the IDE).

    Everything else is fixed and therefore not exposed: the file always lands in the project's
    `.run/` directory named after the configuration, the script always inherits the IDE's
    environment, and it always runs in the Run tool window rather than a terminal.


    This tool writes that file directly and the IDE picks it up live (its `*.run.xml` watcher
    registers the new configuration, usually within a second or two). The result reports whether
    the IDE has loaded it yet and whether the platform accepts running it.

    The name must be free; when it is taken the call fails and suggests a unique one
    (`RunManager.suggestUniqueName` semantics). Templates cannot be cloned.

    There is deliberately no delete tool: deleting a configuration stays a user action in the IDE.
    After creating, use `rerun_run_configuration` to start it and `list_running_configurations` to
    see it running.
    """.trimIndent(),
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.create_shell_run_configuration",
    schema(
        properties = properties(
            "configurationName" to property(
                "string",
                "Name of the new run configuration. Must not collide with an existing one.",
            ),
            "scriptPath" to property(
                "string",
                "Script file to run (relative to the project root, or absolute). Give exactly one of " +
                    "`scriptPath` / `scriptText` / `cloneFrom`.",
            ),
            "scriptText" to property(
                "string",
                "Inline script to run instead of a file. Give exactly one of " +
                    "`scriptPath` / `scriptText` / `cloneFrom`.",
            ),
            "cloneFrom" to property(
                "string",
                "Name of an existing Shell configuration to duplicate (its script, options, environment and " +
                    "before-launch steps are copied, so the result is immediately runnable). Give exactly one of " +
                    "`scriptPath` / `scriptText` / `cloneFrom`.",
            ),
            "scriptOptions" to property(
                "string",
                "Arguments passed to the script. Omit for none.",
            ),
            "workingDirectory" to property(
                "string",
                "Directory the script runs in (relative to the project root, or absolute). " +
                    "Omit for the template default.",
            ),
            "interpreterPath" to property(
                "string",
                "Interpreter to run it with (e.g. `/bin/zsh`). Omit for the template default.",
            ),
            "interpreterOptions" to property(
                "string",
                "Options passed to the interpreter. Omit for none.",
            ),
            "envs" to property(
                "object",
                "Extra environment variables as a JSON object of string to string " +
                    "(e.g. `{\"PORT\": \"3201\"}`). They are merged over the inherited environment unless " +
                    "inherits from the IDE.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("configurationName"),
    ),
    schema(
        properties = properties(
            "configurationName" to property("string", "Name of the created run configuration."),
            "type" to property("string", "Always `ShConfigurationType`: this tool only creates Shell configurations."),
            "typeDisplayName" to property("string", "Always `Shell Script`."),
            "createdFrom" to property(
                "string",
                "How it was created: `script:<path>` / `script-text` / `clone:<source name>`.",
            ),
            "storagePath" to property("string", "Absolute path of the `.run.xml` file the configuration was stored in."),
            "shared" to property("boolean", "Always true for this tool: it is a project-level configuration."),
            "uniqueID" to property("string", "The IDE's internal id of this configuration."),
            "canRun" to property(
                "boolean",
                "Whether the platform's own `checkSettings` accepts running it right now. Note this is only the " +
                    "platform's check.",
            ),
            "canRunBlockedReason" to nullableProperty(
                "string",
                "Why it cannot run yet; null when `canRun` is true. E.g. the script file does not exist.",
            ),
            "beforeLaunchSteps" to property("integer", "How many before-launch steps it carries (copied when cloning)."),
            "warnings" to WARNINGS_SCHEMA,
        ),
        required = setOf("configurationName", "type", "typeDisplayName", "createdFrom", "storagePath", "shared", "uniqueID", "canRun"),
    ),
    annotations(readOnly = false, destructive = false, idempotent = false),
)

/** 默认等待窗口。给 MCP 的调用设一个上限，避免 agent 卡在一个永不退出的进程上。 */
internal const val DEFAULT_WAIT_TIMEOUT_MS = 5_000

/**
 * 默认启动等待上限。
 *
 * **取 10000，与原生一致**：内置 `execute_run_configuration` 的 `timeout` 默认就是 `10000`
 * （`ExecutionToolset` 字节码里的 `sipush 10000`）。调用方可以覆盖它——比如某个配置的 before-launch
 * 要先编译几十秒——但**整次调用的时长要留在服务器的耐心之内**：
 *
 * 实测一个请求悬着超过约 15 秒，MCP Server 会先移除会话（`Pending StreamableHttp transport timed out`
 * → `Removing session`），于是工具算完也送不回结果，客户端拿到 **HTTP 404 空响应**。
 * 实测量到的边界：8 秒的调用能正常送达、20 秒的不能；**连官方工具也一样** ——
 * `execute_run_configuration(waitForExit=true, timeout=30000)` 实测 30.1 秒后拿到空 404。
 *
 * 所以这个值不是"越大越好"：它只决定"我们等多久就放弃并如实报告"，放弃得早一点反而能拿到
 * 一个可读的结构化错误，而不是让整个回答丢失。
 */
internal const val DEFAULT_START_TIMEOUT_MS = 10_000

internal fun defaultWaitTimeoutMs(): Int = DEFAULT_WAIT_TIMEOUT_MS

internal fun defaultStartTimeoutMs(): Int = DEFAULT_START_TIMEOUT_MS

// ---------------------------------------------------------------------------
// 供测试使用：把 JsonObject 里的某个键读回字符串的小工具
// ---------------------------------------------------------------------------

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObject

/**
 * 取一个"字符串到字符串"的 JSON 对象（环境变量用）。
 *
 * 非字符串的值直接跳过（不报错）：agent 传数字 `{"PORT": 3201}` 是常见写法，
 * 为这种小事拒绝整个调用不值；真有问题 `checkSettings` 那边会拦。
 */
internal fun JsonObject.stringMap(key: String): Map<String, String> =
    obj(key)?.entries?.mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
    }?.toMap().orEmpty()
