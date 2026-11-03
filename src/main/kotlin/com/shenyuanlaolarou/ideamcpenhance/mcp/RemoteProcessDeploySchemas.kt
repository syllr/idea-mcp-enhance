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

    Use it when the user asks to deploy / publish / release; use `rerun_run_configuration` when they
    ask to run / debug / restart.

    It syncs the deployment, starts the process / container, and opens a Run tab so the user can
    watch the logs. That session is deploy-only: stopping it closes the log follower and leaves the
    remote process / container running. If the process / container is already running it is replaced
    without asking — `rerun_run_configuration` would ask first.

    The result carries the pid or container and `fullOutputPath`, the local aggregated log (progress
    lines plus the remote stdout/stderr), same field and format as `rerun_run_configuration`. The
    application's own logs are in the configured Application log directory — `list_remote_processes`
    reports it as `logDir`.

    `configurationName` must name a Remote Process configuration in this project.
    """.trimIndent(),
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
            "started" to property("boolean", "Whether the new process really came up."),
            "submittedOnly" to property("boolean", "The start was submitted but the process never came up within the wait window; `started=false` does not mean it failed."),
            "sessionId" to property("string", "Run session identifier; pass it to `stop_run_configuration`."),
            "pid" to property("integer", "Remote pid of the started process (process kind)."),
            "container" to property("string", "Container name used by the docker kind."),
            "workingDirectory" to property("string", "Working directory of the process kind."),
            "fullOutputPath" to property(
                "string",
                "Local aggregated log: progress lines plus the remote stdout/stderr; same field and format as `rerun_run_configuration`.",
            ),
            "output" to property("string", "Inline preview of this start's output."),
        ),
        required = setOf("configurationName", "server", "kind", "started", "output"),
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
    processes that were started outside the IDE. Each call opens an SSH connection per server, so
    query it once and reuse the answer rather than polling it in a loop.

    `status` is `running`, `stopped`, or `invalid` — `invalid` means the configuration cannot be
    queried (no server / no command / no container name / unsupported kind); the `error` field says
    why. `logDir` is the application's own log directory: a host path for `process`, a container path
    for `docker`.

    Use `list_running_configurations` instead to see which Run sessions the IDE currently has.

    Optional filters: `server` and `configurationName`.
    """.trimIndent(),
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
