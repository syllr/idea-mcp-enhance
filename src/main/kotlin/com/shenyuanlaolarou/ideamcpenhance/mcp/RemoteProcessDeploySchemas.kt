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
    Deploy a Remote Process run configuration, exactly like the configuration page's Deploy button.

    Use this when the user asks to deploy / publish / release code. Use the normal run configuration
    tools (`rerun_run_configuration`) when they ask to run / debug / restart.

    It opens a Run tab like the manual Deploy button, so the user can watch the logs. The Run session
    is marked as deploy-only: pressing Stop closes the log follower but does **not** kill the remote
    process / container. When the process / container is already running, the MCP call replaces it
    without asking; the configuration page button still asks.

    The result carries `fullOutputPath` — the local aggregated log (progress lines plus the remote
    stdout/stderr), same field and format as `rerun_run_configuration`. Application logs live in the
    configured Application log directory; find them with `list_remote_processes`.

    If the process / container is already running it is replaced without asking (deploy semantics).
    """.trimIndent(),
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.deploy_remote_process",
    schema(
        properties = properties(
            "projectPath" to projectPathProperty(),
            "configurationName" to property("string", "Remote Process 运行配置名。"),
        ),
        required = setOf("configurationName"),
    ),
    schema(
        properties = properties(
            "configurationName" to property("string", "Remote Process 运行配置名。"),
            "server" to property("string", "SSH 配置名。"),
            "kind" to property("string", "process 或 docker。"),
            "started" to property("boolean", "新进程是否真的起来了。"),
            "submittedOnly" to property("boolean", "启动已提交但还没等到进程起来；此时 started=false 不代表失败。"),
            "sessionId" to property("string", "Run 会话标识，可传给 stop_run_configuration。"),
            "pid" to property("integer", "process 类型启动后的远端 PID。"),
            "container" to property("string", "docker 类型使用的容器名。"),
            "workingDirectory" to property("string", "process 类型的工作目录。"),
            "fullOutputPath" to property(
                "string",
                "本机聚合日志：进度 + 远端 stdout/stderr；和 rerun_run_configuration 的字段一致。",
            ),
            "output" to property("string", "本次启动的内联输出预览。"),
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
            "configurationName" to property("string", "Remote Process 配置名。"),
            "server" to property("string", "SSH 配置名；配置不完整时为空串。"),
            "deployment" to property("string", "绑定的 deployment 名。"),
            "kind" to property("string", "process 或 docker。"),
            "status" to property("string", "running / stopped / invalid。"),
            "pid" to property("integer", "process 运行时的远端 PID。"),
            "container" to property("string", "docker 容器名。"),
            "workingDirectory" to property("string", "process 的工作目录。"),
            "logDir" to property("string", "应用自己的日志目录；process 是宿主机路径，docker 是容器内路径。"),
            "error" to property("string", "status=invalid 时的原因。"),
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
    List every Remote Process run configuration in this project together with its live state.

    Stateless: each call probes the remote host — `ps` + cwd for `process`, `docker ps` for `docker`.
    Stopped configurations are included. This does not use the Run lifecycle, so a configuration
    started by `deploy_remote_process`, by the IDE Run button, or by `rerun_run_configuration`
    appears the same way.

    Optional filters: `server` and `configurationName`.
    """.trimIndent(),
    RUN_LIFECYCLE_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.list_remote_processes",
    schema(
        properties = properties(
            "projectPath" to projectPathProperty(),
            "server" to property("string", "可选：只列这台 SSH 配置上的。"),
            "configurationName" to property("string", "可选：只列这一个配置。"),
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
