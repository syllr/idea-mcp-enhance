package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolDescriptor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `deploy_remote_process` 的对外契约。
 *
 * 它和配置页的 Deploy 按钮一致：同步 deployment、启动进程 / 容器、打开 Run 标签页；
 * 区别只是 Stop 只关闭日志跟随，不 kill 远端。适合"发布"这种动作。
 */
internal const val DEPLOY_REMOTE_PROCESS_TOOL_NAME = "deploy_remote_process"

internal val deployRemoteProcessDescriptor = McpToolDescriptor(
    DEPLOY_REMOTE_PROCESS_TOOL_NAME,
    "Deploy Remote Process",
    """
    Deploy a Remote Process run configuration — the same thing the configuration page's Deploy button
    does.

    Use this to **publish**. The project needs only one rule: publish with the remote `{app}@{env}`
    configuration (this tool); debug or restart with the application's own configuration
    (`rerun_run_configuration`). The configuration type decides which tool applies — not the
    situation, and not a judgement call.

    On trigger it starts the whole deployment — sync, before-launch steps, launch — and opens a Run
    tab so the user can watch the logs. That session is deploy-only: stopping it closes the log
    follower and leaves the remote process / container running. If the process / container is already
    running it is replaced without asking.

    **It returns as soon as the start is triggered**, not when the deployment finished: the sync, the
    upload and the launch keep running in the background. `triggered: true` with `started: null` says
    exactly that. Read `fullOutputPath` for the progress and startup log. To tell whether this deploy
    has actually replaced the remote process, compare `triggeredAt` with `list_remote_processes`'
    `startedAt` — a `startedAt` at or after `triggeredAt` means the new one is running; until then
    you are looking at the previous instance. Poll with a growing backoff (2s, 4s, 8s, 16s).

    `beforeRunTasks[]` carries the same shape as `rerun_run_configuration`: one entry per before-launch
    step, with `name`, `enabled`, `output` (a tail preview of that step's own log) and `lastRun`
    (`exitCode` / `logFile`). `steps[]` is the deploy-specific view: every before-launch build plus
    the deploy itself, in execution order. Before-launch builds run as **separate Run sessions**, so
    their output never appears in `fullOutputPath`.

    `configurationName` must name a Remote Process configuration in this project.
    """.trimIndent(),
    "Deploy files and start a remote process.",
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.deploy_remote_process",
    schema(
        properties = properties(
            "projectPath" to projectPathProperty(),
            "configurationName" to property("string", "Name of the Remote Process run configuration."),
        ),
        required = setOf("configurationName"),
    ),
    schema(
        properties = properties(
            "configurationName" to property("string", "Name of the Remote Process run configuration."),
            "server" to property("string", "SSH configuration name."),
            "kind" to property("string", "process or docker."),
            "triggered" to property(
                "boolean",
                "Whether the platform accepted the start request (a Run session exists). This is the " +
                    "normal success signal for a deploy; `triggered: false` means it never reached the platform.",
            ),
            "triggeredAt" to property(
                "integer",
                "When this deploy was triggered, in epoch milliseconds. Pass it to `list_remote_processes` by " +
                    "eye: a `startedAt` at or after this moment means the new process is the one running.",
            ),
            "triggeredAtIso" to property("string", "When this deploy was triggered, in ISO-8601."),
            "started" to nullableProperty(
                "boolean",
                "Whether the new process was confirmed up. Always `null` here: a deploy returns once the start " +
                    "is triggered, so confirm the service yourself via `list_remote_processes` and `fullOutputPath`.",
            ),
            "sessionId" to nullableProperty("string", "Run session identifier; pass it to `stop_run_configuration`."),
            "pid" to nullableProperty(
                "integer",
                "Remote pid. null when the start was only triggered and the remote process has not reported " +
                    "one yet — poll `list_remote_processes`.",
            ),
            "container" to property("string", "Container name used by the docker kind."),
            "workingDirectory" to property("string", "Working directory of the process kind."),
            "fullOutputPath" to nullableProperty(
                "string",
                "Local aggregated log: progress lines plus the remote stdout/stderr. It keeps growing in the " +
                    "background after this call returns — read it for the startup log.",
            ),
            "output" to property(
                "string",
                "Inline preview captured up to the moment the start was triggered; usually short or empty. " +
                    "The real startup log is `fullOutputPath`.",
            ),
            "steps" to arrayOf(
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("description", JsonPrimitive(
                        "One step of this deploy: a before-launch build, or the deploy itself.",
                    ))
                    put("properties", properties(
                        "kind" to property("string", "`beforeLaunch` or `deploy`."),
                        "name" to property("string", "Step name: the launched configuration, or this one."),
                        "logFile" to nullableProperty(
                            "string",
                            "Absolute path of this step's own log. Before-launch steps write to a separate " +
                                "file from the deploy itself; read it for the build output.",
                        ),
                        "exitCode" to nullableProperty("integer", "Exit code of the step, when it has finished."),
                        "enabled" to property("boolean", "false when the step is switched off in the IDE."),
                        "output" to nullableProperty(
                            "string",
                            "Tail preview of this step's log (at most 10000 characters), prefixed with " +
                                "`<truncated at the start>` when cut. Only before-launch steps carry it; " +
                                "the deploy step's own output is `fullOutputPath`.",
                        ),
                    ))
                    put("required", buildJsonArray {
                        add(JsonPrimitive("kind"))
                        add(JsonPrimitive("name"))
                        add(JsonPrimitive("logFile"))
                        add(JsonPrimitive("exitCode"))
                        add(JsonPrimitive("enabled"))
                        add(JsonPrimitive("output"))
                    })
                },
                "Steps of this deploy in execution order. Before-launch steps come first.",
            ),
            "beforeRunTasks" to arrayOf(
                BEFORE_RUN_STEP_SCHEMA,
                "Before-launch steps with their most recent run — same shape as `rerun_run_configuration`. " +
                    "Use `output` for the build log tail and `lastRun.exitCode` for whether it passed.",
            ),
        ),
        required = setOf(
            "configurationName",
            "server",
            "kind",
            "triggered",
            "triggeredAt",
            "triggeredAtIso",
            "started",
            "output",
            "steps",
            "beforeRunTasks",
        ),
    ),
    annotations(readOnly = false, destructive = false, idempotent = false),
)

// ---------------------------------------------------------------------------
// list_remote_processes
// ---------------------------------------------------------------------------

internal const val LIST_REMOTE_PROCESSES_TOOL_NAME = "list_remote_processes"

private val REMOTE_PROCESS_ITEM_SCHEMA = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One Remote Process configuration and its live state."))
    put(
        "properties",
        properties(
            "configurationName" to property("string", "Remote Process configuration name."),
            "server" to property("string", "SSH configuration name; empty when the configuration is incomplete."),
            "deployment" to property("string", "Deployment it is bound to."),
            "kind" to property("string", "process or docker."),
            "status" to property("string", "running / stopped / invalid。"),
            "pid" to property("integer", "Remote pid while the process is running."),
            "container" to property("string", "Docker container name."),
            "workingDirectory" to property("string", "Working directory of the process."),
            "startedAt" to nullableProperty(
                "string",
                "When the container / process currently running was started (ISO-8601, UTC). Compare it with " +
                    "a deploy's `triggeredAt`: a value at or after that moment means this deploy's process is " +
                    "the one now running. null when stopped or not queryable.",
            ),
            "logDir" to property("string", "Directory the application writes its own logs to; a host path for process, a container path for docker."),
            "error" to property("string", "Why `status` is invalid."),
        ),
    )
    put(
        "required",
        buildJsonArray {
            add(JsonPrimitive("configurationName"))
            add(JsonPrimitive("server"))
            add(JsonPrimitive("kind"))
            add(JsonPrimitive("status"))
        },
    )
}

internal val listRemoteProcessesDescriptor = McpToolDescriptor(
    LIST_REMOTE_PROCESSES_TOOL_NAME,
    "List Remote Processes",
    """
    List every Remote Process run configuration in this project with its live state on the remote host.

    It does not go through the Run lifecycle: it probes the remote directly — `ps` + cwd for
    `process`, `docker ps` for `docker` — so it also reports configurations that are not running, and
    processes that were started outside the IDE. Each call opens an SSH connection per server, so do
    not poll it in a tight loop — to confirm a start, poll with a 2s / 4s / 8s / 16s backoff and reuse
    each answer.

    `status` is `running`, `stopped`, or `invalid` — `invalid` means the configuration cannot be
    queried (no server / no command / no container name / unsupported kind); the `error` field says
    why. `logDir` is the application's own log directory: a host path for `process`, a container path
    for `docker`.

    Use `list_running_configurations` instead to see which Run sessions the IDE currently has.

    Optional filters: `server` and `configurationName`.
    """.trimIndent(),
    "List processes running on a remote host.",
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.list_remote_processes",
    schema(
        properties = properties(
            "projectPath" to projectPathProperty(),
            "server" to property("string", "Optional: only the configurations on this SSH server."),
            "configurationName" to property("string", "Optional: only this one configuration."),
        ),
    ),
    schema(
        properties = properties(
            "processes" to arrayOf(REMOTE_PROCESS_ITEM_SCHEMA, "One Remote Process configuration."),
        ),
        required = setOf("processes"),
    ),
    annotations(readOnly = true, destructive = false, idempotent = true),
)
