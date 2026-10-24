package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Deployment 工具的**描述符**：名字、说明、输入 schema、输出 schema、行为注解。
 *
 * 与 `RunLifecycleSchemas` 同样的理由集中在单独文件里：描述文案本身就是对外契约，
 * agent 靠它决定"该不该调、参数怎么填、返回怎么读"。
 *
 * 输出 schema 必须和 `DeploymentModels` 里 `toJson()` 逐字段对齐 —— 两边漂移会让严格客户端
 * 在一个完全正常的返回上校验失败。
 */

/** 工具分组。`alwaysIncluded=false`：不勾选也能被主动选用，不污染默认工具列表。 */
internal val DEPLOYMENT_CATEGORY = McpToolCategory(
    "Deployment",
    "com.shenyuanlaolarou.ideamcpenhance.mcp.Deployment",
    false,
    false,
)

// ---------------------------------------------------------------------------
// 输出 schema 片段
// ---------------------------------------------------------------------------

/** 一个 deployment 的对外名称，对应 `DeploymentMappingState.toJson()`。 */
private val DEPLOYMENT_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One deployment attached to this server."))
    put("properties", properties(
        "name" to property(
            "string",
            "Deployment name derived from its local path; pass this to upload_to_deployment / " +
                "download_from_deployment as `deploymentName`.",
        ),
    ))
    put("required", buildJsonArray { add(JsonPrimitive("name")) })
}

/** 一个 Server 以及它在本项目下挂的 deployments，对应 `DeploymentServerState.toJson()`。 */
private val DEPLOYMENT_SERVER_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One server and its deployments in this project."))
    put("properties", properties(
        "name" to property(
            "string",
            "Server name. Pass THIS to the other deployment tools (diff/upload/download) to target it; " +
                "the id is a machine-local UUID and is not reusable elsewhere.",
        ),
        "id" to property("string", "Machine-local server UUID. Informational only."),
        "level" to property("string", "project = visible to this project only; application = shared by all projects."),
        "accessType" to property("string", "SFTP, FTP, FTPS, WEBDAV, MOUNT or LOCAL."),
        "host" to nullableProperty("string", "Host name; null when it could not be read."),
        "port" to property("integer", "Port number; 0 when it could not be read."),
        "rootFolder" to nullableProperty("string", "Server root folder that deployment paths are relative to."),
        "needsTransfer" to property(
            "boolean",
            "false for LOCAL servers: they do not transfer anything over the network, so upload/download are no-ops.",
        ),
        "hasCredentials" to property(
            "boolean",
            "Whether usable credentials are known to the IDE. When false, connecting opens a modal login dialog, " +
                "which would block the MCP call.",
        ),
        "validationIssue" to nullableProperty(
            "string",
            "Result of the IDE's own offline validation; non-null means the server is misconfigured and the IDE " +
                "itself would exclude it from ``upload to default server``.",
        ),
        "isDefault" to property("boolean", "true for the server (or a member of the group) currently set as default."),
        "deployments" to arrayOf(
            DEPLOYMENT_SCHEMA,
            "Deployments explicitly configured for this server in this project. Empty means the server can " +
                "still be used by `execute_in_remote_host`, but upload/download have no target.",
        ),
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("name"))
        add(JsonPrimitive("id"))
        add(JsonPrimitive("level"))
        add(JsonPrimitive("accessType"))
        add(JsonPrimitive("host"))
        add(JsonPrimitive("port"))
        add(JsonPrimitive("rootFolder"))
        add(JsonPrimitive("needsTransfer"))
        add(JsonPrimitive("hasCredentials"))
        add(JsonPrimitive("validationIssue"))
        add(JsonPrimitive("isDefault"))
        add(JsonPrimitive("deployments"))
    })
}

/** 项目级选项，对应 `DeploymentOptionsState.toJson()`。 */
private val DEPLOYMENT_OPTIONS_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put(
        "description",
        JsonPrimitive(
            "Project-wide deployment options. These are shared by EVERY server in this project — unlike " +
                "`deployments`, which are per server.",
        ),
    )
    put("properties", properties(
        "autoUpload" to property("string", "ALWAYS, ON_EXPLICIT_SAVE or NEVER."),
        "overwriteAll" to property(
            "boolean",
            "true = skip the size+mtime check and re-upload every file on each upload.",
        ),
        "deleteMissingItems" to property(
            "boolean",
            "true = files that exist on the server but not locally are deleted during an upload (mirror effect).",
        ),
        "preserveTimestamps" to property(
            "boolean",
            "true = the remote modification time is set to the local one after upload, which is what allows the " +
                "next upload to skip unchanged files.",
        ),
        "confirmBeforeUploading" to property("boolean", "Whether the IDE asks before uploading."),
        "confirmBeforeDeletion" to property(
            "boolean",
            "Whether the IDE asks before deleting remote files. true blocks the MCP path (a modal dialog nobody " +
                "can answer).",
        ),
        "autoUploadExternalChanges" to property(
            "boolean",
            "true = changes made by external tools (VCS, scripts) also trigger automatic upload.",
        ),
        "remoteFilesAllowedToDisappearOnAutoupload" to property(
            "boolean",
            "true = automatic upload may delete remote files whose local counterparts disappeared.",
        ),
        "excludeMask" to nullableProperty("string", "Exclude mask applied to uploads."),
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("autoUpload"))
        add(JsonPrimitive("overwriteAll"))
        add(JsonPrimitive("deleteMissingItems"))
        add(JsonPrimitive("preserveTimestamps"))
        add(JsonPrimitive("confirmBeforeUploading"))
        add(JsonPrimitive("confirmBeforeDeletion"))
        add(JsonPrimitive("autoUploadExternalChanges"))
        add(JsonPrimitive("remoteFilesAllowedToDisappearOnAutoupload"))
        add(JsonPrimitive("excludeMask"))
    })
}

private val DEPLOYMENT_TASK_RESULT_PROPERTIES: JsonObject = properties(
    "handle" to property("string", "Handle of this transfer; pass it to `get_deployment_task_status`."),
    "kind" to property("string", "`upload` (to the server) or `download` (from the server)."),
    "serverName" to property("string", "Server the transfer ran against."),
    "mode" to property(
        "string",
        "Policy used: `upload`/`mirror` for uploads, `take-remote` for downloads.",
    ),
    "localRoots" to arrayOf(
        property("string", "Local directory involved."),
        "Local roots taken from the server's path mappings. Empty when read back from a status query.",
    ),
    "state" to property(
        "string",
        "`completed` (the transfer really finished, the counts below are meaningful) or `in_progress` " +
            "(still running: `totalProcessed` and `counters` are zero and mean nothing).",
    ),
    "elapsedMs" to property("integer", "How long this transfer has been running, in milliseconds."),
    "processed" to property(
        "integer",
        "Operations handled so far, live while running; 0 until the platform has counted its first one.",
    ),
    "total" to property("integer", "Expected operation count as reported by the platform; 0 when unknown."),
    "succeeded" to property("boolean", "The platform reported no failed operations."),
    "totalProcessed" to property("integer", "Number of operations the platform executed."),
    "failed" to property("integer", "Number of operations that failed."),
    "counters" to buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "description",
            JsonPrimitive(
                "Per-operation counts as recorded by the platform, keyed by its own bundle keys — e.g. " +
                    "{\"transfer.details.transferred\": 19, \"transfer.details.deleted\": 1}. Each number counts " +
                    "operations, not files: `deleted` counts delete operations, and a removed directory counts " +
                    "as 1 however many files it held.",
            ),
        )
        put("additionalProperties", property("integer", "Count for this operation kind."))
    },
    "warnings" to WARNINGS_SCHEMA,
)

private val DEPLOYMENT_TASK_RESULT_REQUIRED: Set<String> = setOf(
    "handle",
    "kind",
    "serverName",
    "mode",
    "localRoots",
    "state",
    "elapsedMs",
    "processed",
    "total",
    "succeeded",
    "totalProcessed",
    "failed",
    "counters",
    "warnings",
)


// ---------------------------------------------------------------------------
// list_server_deployments
// ---------------------------------------------------------------------------

internal const val LIST_SERVER_DEPLOYMENTS_TOOL_NAME = "list_server_deployments"

internal val listServerDeploymentsDescriptor = McpToolDescriptor(
    LIST_SERVER_DEPLOYMENTS_TOOL_NAME,
    "List Server Deployments",
    """
    List servers usable from this project, each with its deployments.

    A server is the connection target; deployments are path mappings. A server may have zero
    deployments: `execute_in_remote_host` still works, but upload/download need one.

    Remote terminal: pass `servers[].name` as `serverName`.
    Upload/download: pass `servers[].deployments[].name` as `deploymentName`.

    Each server reports level, accessType, host/port, `deployments[]`, `hasCredentials` and
    `validationIssue`. A server with `hasCredentials: false` opens a modal login dialog and blocks
    the MCP call, so pass only servers that report credentials. `options` are project-wide;
    `warnings` flags risky combinations and servers without deployments.

    Read-only; no network access.
    """.trimIndent(),
    DEPLOYMENT_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.list_server_deployments",
    schema(
        properties = properties(
            "projectPath" to projectPathProperty(),
        ),
    ),
    schema(
        properties = properties(
            "defaultServerOrGroupName" to nullableProperty(
                "string",
                "Name of the server or group currently used as the default; null when none is configured.",
            ),
            "serverCount" to property("integer", "Number of servers reported."),
            "servers" to arrayOf(DEPLOYMENT_SERVER_SCHEMA, "Every server usable from this project."),
            "options" to DEPLOYMENT_OPTIONS_SCHEMA,
            "warnings" to WARNINGS_SCHEMA,
        ),
        required = setOf("defaultServerOrGroupName", "serverCount", "servers", "options", "warnings"),
    ),
    annotations(readOnly = true, destructive = false, idempotent = true),
)

// ---------------------------------------------------------------------------
// upload_to_deployment
// ---------------------------------------------------------------------------

internal const val UPLOAD_TO_DEPLOYMENT_TOOL_NAME = "upload_to_deployment"

internal val uploadToDeploymentDescriptor = McpToolDescriptor(
    UPLOAD_TO_DEPLOYMENT_TOOL_NAME,
    "Upload To Deployment",
    """
    Upload one deployment to its remote target — the IDE's "Deployment | Upload to ...".

    Resolve `deploymentName` from `list_server_deployments.servers[].deployments[].name`;
    deployment names are globally unique.

    `mode`:
    - `"mirror"` (**default**): upload local files **and delete remote extras**. **Destructive**;
      no confirmation step here.
    - `"upload"`: send local files only.

    The call waits for the transfer and returns as soon as it finishes → `state: "completed"`.
    Only a transfer that outlives the wait window comes back as `state: "in_progress"` plus `handle`; poll
    `get_deployment_task_status`. A second upload starts another transfer; an upload already
    running for the same server returns its existing handle.

    `counters` count operations, not files (a removed directory is one delete); counts are
    meaningless while in progress. Project-wide settings control skipping/confirmation; this
    tool disables deletion confirmation. The server must be reachable with usable credentials.
    """.trimIndent(),
    DEPLOYMENT_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.upload_to_deployment",
    schema(
        properties = properties(
            "deploymentName" to property(
                "string",
                "Deployment name from `list_server_deployments.servers[].deployments[].name`.",
            ),
            "mode" to property(
                "string",
                "\"mirror\" (default) uploads local files AND deletes remote files the project no longer " +
                    "contains — destructive; \"upload\" sends local files only and leaves remote extras alone.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("deploymentName"),
    ),
    schema(DEPLOYMENT_TASK_RESULT_PROPERTIES, DEPLOYMENT_TASK_RESULT_REQUIRED),
    annotations(readOnly = false, destructive = true, idempotent = false, openWorld = true),
)

// ---------------------------------------------------------------------------
// get_deployment_task_status
// ---------------------------------------------------------------------------

internal const val GET_TASK_STATUS_TOOL_NAME = "get_deployment_task_status"

internal val getDeploymentTaskStatusDescriptor = McpToolDescriptor(
    GET_TASK_STATUS_TOOL_NAME,
    "Get Deployment Task Status",
    """
    Read the current state of an upload or download task by its handle.

    This tool only reads state. Use it to poll a task that came back with
    `state: "in_progress"`; the handle identifies that one task, even when a server has many.
    `kind` tells you whether it is an upload or a download.

    While running, `processed` / `total` come from platform counters (`total` may still be 0 while
    counting). When finished, `counters` are per-operation counts, not file counts — a removed
    directory counts as one delete.

    An unknown handle, or a finished task older than the 20 most recent per project, returns an error.
    """.trimIndent(),
    DEPLOYMENT_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.get_deployment_task_status",
    schema(
        properties = properties(
            "handle" to property(
                "string",
                "Handle returned by `upload_to_deployment`, e.g. `dep-1a2b3c4d`.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("handle"),
    ),
    schema(DEPLOYMENT_TASK_RESULT_PROPERTIES, DEPLOYMENT_TASK_RESULT_REQUIRED),
    annotations(readOnly = true, destructive = false, idempotent = true),
)


// ---------------------------------------------------------------------------
// download_from_deployment
// ---------------------------------------------------------------------------

/**
 * 三个 deployment 工具（上传 / 下载 / 查状态）**共用**的返回 schema。
 *
 * 抽成常量是为了让它们**不可能漂移**：三者的返回字段本来就应该一模一样，区别只在"谁触发的"。
 */
internal const val DOWNLOAD_TOOL_NAME = "download_from_deployment"

internal val downloadFromDeploymentDescriptor = McpToolDescriptor(
    DOWNLOAD_TOOL_NAME,
    "Download From Deployment",
    """
    Pull one deployment (one server + one path mapping) into this project — the same operation as
    the IDE's "Deployment | Download from ...".

    Resolve `deploymentName` from `list_server_deployments.servers[].deployments[].name` first.
    Deployment names are globally unique in the project.

    Files present on both sides are replaced by the server's copy when they differ; in-sync files are
    left alone. There is no "keep my local version" option: a dialog cannot be answered on the MCP path,
    so the server is always the source of truth.

    The call waits for the transfer and returns as soon as it finishes:
    - finished → `state: "completed"`.
    - wait window ran out → `state: "in_progress"` with a `handle`; the download keeps running. Poll it with
      `get_deployment_task_status`. A second download starts another transfer; a transfer already
      running for the same server returns its existing handle instead.
    """.trimIndent(),
    DEPLOYMENT_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.download_from_deployment",
    schema(
        properties = properties(
            "deploymentName" to property(
                "string",
                "Deployment name from `list_server_deployments.servers[].deployments[].name`.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("deploymentName"),
    ),
    schema(DEPLOYMENT_TASK_RESULT_PROPERTIES, DEPLOYMENT_TASK_RESULT_REQUIRED),
    annotations(readOnly = false, destructive = true, idempotent = false, openWorld = true),
)
