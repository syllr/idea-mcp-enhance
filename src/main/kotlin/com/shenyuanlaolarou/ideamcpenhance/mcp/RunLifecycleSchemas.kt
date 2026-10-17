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
    Report what is **actually running right now** in this project: which configurations have live
    processes, how many each, and with which process id.

    Why this exists: the built-in `get_run_configurations` lists what exists but says nothing about
    execution, and a configuration can be running with only one of its processes visible in the IDE
    (`execute_run_configuration` spawns a new process on every call).

    Everything returned is live: configurations with no running process do not appear at all (which covers
    templates), and `instances[]` holds live instances only — so a `runningInstanceCount` above 1 means the
    configuration was started more than once.

    Use this before `stop_run_configuration` / `rerun_run_configuration`, and after them to confirm.
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

    Why this exists: every instance is stopped, not just the newest one — a configuration can easily have
    several live processes while the IDE shows one, and stopping a single one would leave its port held.
    The stop goes through the IDE's own `ProcessHandler`, so the Run tool window stays consistent (unlike
    an external `pkill`).

    Idempotent: a configuration that is not running stops successfully with nothing to do
    (`matchedInstances: 0`, `isError: false`), so asking again never turns into an error. One case needs
    care: when a start is **in flight** (submitted, its process not up yet) the tool waits briefly for it,
    and if the process still does not appear it returns that same success **plus a warning** that a start
    may come up shortly — do not read `matchedInstances: 0` as "definitely down", re-check with
    `list_running_configurations`.

    The name is matched exactly first, then case-insensitively; when nothing matches, the error lists the
    names that do exist.
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
                "How long to wait for the processes to exit, in milliseconds. Defaults to 5000. Values <= 0 fall " +
                    "back to the default. A value beyond the MCP server's patience (roughly 15 seconds) never " +
                    "produces an answer: the session is dropped before it is over.",
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
    Restart a run configuration — the same observable effect as the IDE's **Rerun** button.

    Semantics: running → stop the live instances, then start again (`mode: RESTARTED`); not running → just
    start it (`mode: STARTED`); the IDE cannot launch this configuration type → nothing happens
    (`mode: NO_OP`). Read `started` — not `mode` — to know whether a new process really came up;
    `started: false` comes with `startFailure`. `transition[]` reports every step in order (what was
    stopped, with its exit codes, then the new process, with timings).

    The call waits until the new process really exists before returning, so `started` is a real answer.

    A restart only makes sense from a settled state, so the call fails fast with a structured error when the
    configuration is **stopping** or **starting** (a run was requested but its process is not up yet), when
    its previous instances could not be stopped (nothing new is started then — that is the state that would
    leave two instances running), or when the IDE itself is already starting it (the platform would silently
    drop a second start, so this call reports it instead of being ignored). All three read the IDE's own
    state, so starts made by hand or by the built-in `execute_run_configuration` are covered too.

    Long before-launch steps (a build, or another run configuration) delay the Run content — the platform
    creates it only after they finish. The whole call must stay inside the MCP server's patience (roughly 15
    seconds; beyond that it drops the session and the caller gets an empty HTTP 404 instead of this answer):
    the total is `stopTimeoutMs` + `startTimeoutMs` + a ~2 second output window, so a slow stop plus a slow
    start already exceeds it. **Whenever the call is going to be that slow — long before-launch steps, or
    instances that take a while to shut down — use the asynchronous mode instead of waiting**: with
    `waitForStart = false` the start is submitted and the call returns right after it is submitted (the old
    instances are still stopped first, within `stopTimeoutMs`), with `started = null` because the outcome is
    unknown, so poll `list_running_configurations` for the real state. Do not shrink `stopTimeoutMs` to fit
    the budget: that only gives up on a graceful shutdown sooner (whatever has not exited is killed).

    The name is matched exactly first, then case-insensitively; when nothing matches, the error lists the
    names that do exist.

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
            "startTimeoutMs" to property(
                "integer",
                "How long to wait for the new process to be created, in milliseconds. Defaults to 10000 — the same " +
                    "default as the built-in `execute_run_configuration`'s `timeout`. Raise it when this configuration " +
                    "has before-launch steps that take a while (a build, or another run configuration): the IDE creates " +
                    "the Run content only after they finish. Mind the whole call's budget, though — see the tool " +
                    "description for what a slow stop plus a slow start does to it.",
            ),
            "waitForStart" to property(
                "boolean",
                "Defaults to true: submit the start and wait until the new process is created, then report " +
                    "`started` / `output`. Set it to false when the whole call would outlast the MCP server's " +
                    "patience (long before-launch steps, or a slow stop — see the tool description): the start is " +
                    "submitted and the tool returns right after that, with `started = null` (unknown), so poll " +
                    "`list_running_configurations` for the real state. Mirrors the built-in " +
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
            "sessionId" to nullableProperty(
                "string",
                "Run session identifier of the new process: the configuration name, or `<name>#<executionId>` " +
                    "when several live instances share that name.",
            ),
            "output" to property("string", "Inline preview of the new process output, at most 10000 characters."),
            "fullOutputPath" to nullableProperty("string", "Path of the full raw output log; it keeps growing while the process is alive."),
            "transition" to arrayOf(RUN_TRANSITION_STEP_SCHEMA, "Steps of this restart, in chronological order."),
            "instances" to arrayOf(
                RUN_INSTANCE_SCHEMA,
                "All Run contents of this configuration after the restart, finished ones included.",
            ),
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
    Create a **project-level** Shell Script run configuration, then start it with
    `rerun_run_configuration`.

    Where it lives is fixed: `<project>/.run/<configurationName>.run.xml`, always project-level.
    **Do not create or edit that file yourself**: a configuration is created only through this tool.
    There is no storage-path parameter, and no delete tool.

    Why this exists: a Shell configuration carries the project's environment (working directory,
    environment variables, interpreter), so a project command that needs that environment can be started
    through one. Use it for commands in the project's **permanent workflow** — a build, a dependency
    install, a dev server — not for a one-off command.

    **Ask the user before creating anything.** A run configuration is a durable artefact and a project
    normally has only a handful of them. State what you intend to create, what it will run and why it is
    worth keeping, then wait for the user's agreement. For a one-off command, run it another way.

    Only Shell Script configurations can be created — there is no `type` parameter. Give exactly one of
    `scriptPath` (a script file, which should exist — otherwise it is created but cannot run yet),
    `scriptText` (an inline script) or `cloneFrom` (copy an existing Shell configuration; the source must be
    a Shell configuration, not a template). The remaining parameters are documented on themselves.

    Everything else is fixed: the script always inherits the IDE's environment and runs in the Run tool
    window rather than a terminal. `canRun` in the result is the platform's own check — it is also false
    while the IDE has not loaded the new file yet. The name must be free; when it is taken the call fails
    and suggests a unique one.
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
                    "(e.g. `{\"PORT\": \"3201\"}`). They are merged over the environment the script " +
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
