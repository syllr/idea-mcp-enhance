package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Deployment（FTP/SFTP/WebDAV Connectivity）工具的**纯数据层**。
 *
 * 与 `RunLifecycleModels` 同一条分工：本文件不含任何平台类型、不做 IO，只负责"数据 → JSON"。
 * 平台接线在 `DeploymentAccess`，那里是**唯一** import `com.jetbrains.plugins.webDeployment.*` 的地方。
 *
 * 这样切分的实际收益：agent 依赖的是返回 JSON 的形状，把它做成纯函数就能用纯 JUnit 逐字段断言，
 * 不需要真的配一个 SFTP 服务器去跑。风险提示（[deploymentWarnings]）也因此在纯 JUnit 里可测。
 *
 * ## 一个必须先说清的概念：**哪些配置是按 server 的**
 *
 * 不是所有东西都按 server 存。实测（`PublishConfig$State`）：
 *
 * - **按 server**：只有 `Mappings` 与 `Excluded paths`（存在 `ServerData` 里，按 server 名/ID 索引）
 * - **整个项目共用**：其余全部 —— 覆盖策略、删多余、确认开关、自动上传、排除掩码……
 *
 * 所以 [DeploymentOptionsState] 是**项目级**的，不要读成"某个 server 的选项"。
 */

/** 一条路径映射：本地目录 → 远端部署目录（可另带 web 路径）。 */
data class DeploymentMappingState(
    val localPath: String,
    val deployPath: String,
    val webPath: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("localPath", localPath)
        put("deployPath", deployPath)
        put("webPath", webPath)
    }
}

/**
 * 一个 Deployment 服务器的快照（对应 `WebServerConfig` + 它在**本项目**下的 Mappings）。
 *
 * @param name 服务器名。**后续的 diff / upload / download 工具用它当入参** ——
 *   服务器 ID 是生成的本机 UUID（换台机器就不同），只有名字跨机器可读、可复用。
 * @param id 本机 UUID，仅作参考，不要拿它做跨机器匹配
 * @param projectLevel true = 只对这个项目可见（存在 `.idea/webServers.xml`）；false = 应用级（所有项目共享）
 * @param accessType SFTP / FTP / FTPS / WEBDAV / MOUNT / LOCAL
 * @param needsTransfer false 表示这类服务器不产生网络传输（LOCAL 类型），传不了文件
 * @param hasCredentials 有没有可用凭据。**这是认证预检**：false 时不要发起连接，
 *   否则平台会弹模态登录框（`PublishUtils.showLoginDialogAfterAuthFail`），在 MCP 路径上会把调用卡死
 * @param validationIssue `validateFast()` 的就地校验结果（不联网）；非 null 表示配置有毛病，
 *   平台自己也会把它从"可上传的默认服务器"里剔掉
 * @param isDefault 是不是当前默认服务器/组
 * @param mappings 该服务器在**本项目**下的路径映射（来自 `PublishConfig`，不来自 `WebServerConfig`）
 */
data class DeploymentServerState(
    val name: String,
    val id: String,
    val projectLevel: Boolean,
    val accessType: String,
    val host: String?,
    val port: Int,
    val rootFolder: String?,
    val needsTransfer: Boolean,
    val hasCredentials: Boolean,
    val validationIssue: String?,
    val isDefault: Boolean,
    val mappings: List<DeploymentMappingState>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("id", id)
        put("level", if (projectLevel) "project" else "application")
        put("accessType", accessType)
        put("host", host)
        put("port", port)
        put("rootFolder", rootFolder)
        put("needsTransfer", needsTransfer)
        put("hasCredentials", hasCredentials)
        put("validationIssue", validationIssue)
        put("isDefault", isDefault)
        putJsonArray("mappings") { for (mapping in mappings) add(mapping.toJson()) }
    }
}

/**
 * 项目级的部署选项。
 *
 * **它们是整个项目一套，所有 server 共用**（见文件头的说明）——这也是为什么把它放在
 * `list_deployment_servers` 的返回里：它不是"某个服务器"的属性。
 *
 * @param autoUpload ALWAYS / ON_EXPLICIT_SAVE / NEVER
 * @param overwriteAll true = **不做** size+mtime 比较，每次全量覆盖（注意语义与字面相反：
 *   true 时 `isUpToDate` 那段跳过逻辑压根不执行）
 * @param deleteMissingItems true = 本地没有而远端有的文件会被删除（"镜像"效果）
 * @param preserveTimestamps true = 上传后把远端 mtime 设成本地 mtime（这是下次能跳过未变更文件的前提）
 * @param confirmBeforeDeletion true = 删除前弹确认框。**MCP 路径必须规避**：那个框会占住 EDT，
 *   把工具调用连同其它要上 EDT 的工具一起卡死
 * @param autoUploadExternalChanges true = 连 VCS / 外部工具造成的变更也触发自动上传（危险项）
 */
data class DeploymentOptionsState(
    val autoUpload: String,
    val overwriteAll: Boolean,
    val deleteMissingItems: Boolean,
    val preserveTimestamps: Boolean,
    val confirmBeforeUploading: Boolean,
    val confirmBeforeDeletion: Boolean,
    val autoUploadExternalChanges: Boolean,
    val remoteFilesAllowedToDisappearOnAutoupload: Boolean,
    val excludeMask: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("autoUpload", autoUpload)
        put("overwriteAll", overwriteAll)
        put("deleteMissingItems", deleteMissingItems)
        put("preserveTimestamps", preserveTimestamps)
        put("confirmBeforeUploading", confirmBeforeUploading)
        put("confirmBeforeDeletion", confirmBeforeDeletion)
        put("autoUploadExternalChanges", autoUploadExternalChanges)
        put("remoteFilesAllowedToDisappearOnAutoupload", remoteFilesAllowedToDisappearOnAutoupload)
        put("excludeMask", excludeMask)
    }
}

/** `list_deployment_servers` 的完整返回。 */
data class DeploymentServersReport(
    val defaultServerOrGroupName: String?,
    val servers: List<DeploymentServerState>,
    val options: DeploymentOptionsState,
    val warnings: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("defaultServerOrGroupName", defaultServerOrGroupName)
        put("serverCount", servers.size)
        putJsonArray("servers") { for (server in servers) add(server.toJson()) }
        put("options", options.toJson())
        putJsonArray("warnings") { for (warning in warnings) add(JsonPrimitive(warning)) }
    }
}

/**
 * 从当前配置推导**风险提示**。纯函数，便于单测。
 *
 * 这里放的是"用户看配置文件看不出来、但会咬人"的组合。刻意不做成拒绝性错误：
 * 它们描述的是**既有的持久配置**，不是这次调用做错了什么，所以只在返回里点出来。
 */
internal fun deploymentWarnings(
    defaultServerOrGroupName: String?,
    servers: List<DeploymentServerState>,
    options: DeploymentOptionsState,
): List<String> = buildList {
    if (defaultServerOrGroupName == null) {
        add(
            "No default server or group is configured, so `uploadToDefaultServer` and automatic upload have " +
                "no target at all. Pick one in Settings | Build, Execution, Deployment | Deployment.",
        )
    }

    if (options.overwriteAll) {
        add(
            "overwriteAll = true: every file is re-uploaded on each upload (the size+mtime \"skip unchanged\" " +
                "check is bypassed). Uncheck \"Overwrite all files when uploading\" in Deployment | Options to " +
                "upload only changes.",
        )
    }

    if (options.deleteMissingItems && options.autoUploadExternalChanges) {
        add(
            "Dangerous combination: automatic upload reacts to changes made by external tools (VCS, scripts) " +
                "AND deleteMissingItems is on — switching a git branch can delete the corresponding files on " +
                "the remote host.",
        )
    }

    if (options.deleteMissingItems && options.confirmBeforeDeletion) {
        add(
            "deleteMissingItems is on while confirmBeforeDeletion is on: the IDE will show a modal confirmation " +
                "dialog before each deletion. On the MCP path nothing can answer that dialog, so it would block " +
                "the call (and any other tool call that needs the EDT).",
        )
    }

    if (options.autoUpload != "NEVER") {
        add(
            "Automatic upload is '${options.autoUpload}': changing the default server changes the target of every " +
                "subsequent save, immediately. If you rely on automatic upload, keep the default pointed at your " +
                "everyday environment and use this tool's `serverName` argument for one-off uploads.",
        )
    }

    for (server in servers) {
        if (!server.needsTransfer) {
            add(
                "\"${server.name}\" is a ${server.accessType} server, which does not transfer anything over the " +
                    "network — upload/download are no-ops for it.",
            )
        }
        if (server.validationIssue != null) {
            add("\"${server.name}\" has a configuration problem: ${server.validationIssue}")
        }
        if (server.needsTransfer && !server.hasCredentials) {
            add(
                "\"${server.name}\" has no usable credentials. Connecting to it makes the IDE open a modal login " +
                    "dialog, which blocks the MCP call. Fix the credentials in the IDE first.",
            )
        }
        if (server.needsTransfer && server.mappings.isEmpty()) {
            add(
                "\"${server.name}\" has no path mapping in this project, so upload/download would have no files " +
                    "to work on. Add a Mapping in its Deployment settings.",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// upload_to_deployment
// ---------------------------------------------------------------------------

/**
 * `upload_to_deployment` / `download_from_deployment` / `get_deployment_task_status` 三者共用的返回结构。
 *
 * 三个工具返回**同一套字段**——它们的区别只在"谁触发的"，不在"数据长什么样"，这样 agent 只需记一套读法。
 *
 * @param handle 这一次任务的句柄。**查询状态只认它**，不要用 serverName 去查（一个服务器会先后有多个任务）
 * @param kind `upload`（推到服务器）或 `download`（从服务器拉）
 * @param mode 该方向的策略：上传是 `upload`/`mirror`，下载目前只有 `take-remote`
 * @param state `completed`（任务真正结束，结果可信）或 `in_progress`（**还在传**，见下方说明）
 * @param elapsedMs 这个任务已经跑了多久（毫秒）；**已结束的取结束时刻**，不再随时间增长
 * @param processed / total 平台计数器的实时进度。两个方向都拿得到（上传/下载都是我们自己的任务子类）；
 *   收集阶段结束前 `total` 可能还是 0，如实反映，不假装有进度
 * @param counters 平台按操作类型记的计数，key 是它自己的 bundle key（形如
 *   `transfer.details.transferred` / `transfer.details.deleted`）。**粒度是平台定的，不是"文件数"**：
 *   `transferred` 数文件，`deleted` 数删除操作（一个被整个删掉的目录算 1）。
 *   **`state = "in_progress"` 时为空**——还没统计完，报了也没意义
 */
data class DeploymentTaskReport(
    val handle: String,
    val kind: String,
    val serverName: String,
    val mode: String,
    val roots: List<String>,
    val state: String,
    val elapsedMs: Long,
    val processed: Int,
    val total: Int,
    val succeeded: Boolean,
    val totalProcessed: Int,
    val failed: Int,
    val counters: Map<String, Int>,
    val warnings: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("handle", handle)
        put("kind", kind)
        put("serverName", serverName)
        put("mode", mode)
        putJsonArray("localRoots") { for (root in roots) add(JsonPrimitive(root)) }
        put("state", state)
        put("elapsedMs", elapsedMs)
        put("processed", processed)
        put("total", total)
        put("succeeded", succeeded)
        put("totalProcessed", totalProcessed)
        put("failed", failed)
        putJsonObject("counters") { for ((key, value) in counters) put(key, value) }
        putJsonArray("warnings") { for (warning in warnings) add(JsonPrimitive(warning)) }
    }
}
