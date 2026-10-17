package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.plugins.webDeployment.ConnectionOwnerFactory
import com.jetbrains.plugins.webDeployment.DeploymentPathUtils
import com.jetbrains.plugins.webDeployment.ExecutionContext
import com.jetbrains.plugins.webDeployment.IgnoreOverwritingStrategy
import com.jetbrains.plugins.webDeployment.ProjectDeploymentRevisionTracker
import com.jetbrains.plugins.webDeployment.TransferTask
import com.jetbrains.plugins.webDeployment.AlwaysOverwrite
import com.jetbrains.plugins.webDeployment.AlwaysSkip
import com.jetbrains.plugins.webDeployment.actions.DownloadAction
import com.jetbrains.plugins.webDeployment.actions.PublishActionUtil
import com.jetbrains.plugins.webDeployment.connections.RemoteConnectionManager
import com.jetbrains.plugins.webDeployment.config.Deployable
import com.jetbrains.plugins.webDeployment.config.DeploymentPathMapping
import com.jetbrains.plugins.webDeployment.config.FileTransferConfig
import com.jetbrains.plugins.webDeployment.config.GroupedServersConfigManager
import com.jetbrains.plugins.webDeployment.config.PublishConfig
import com.jetbrains.plugins.webDeployment.config.WebServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.commons.vfs2.FileObject
import org.apache.commons.vfs2.FileType

/**
 * Deployment 的**平台接线层**：把 `WebServerConfig` / `PublishConfig` 拍平成 [DeploymentServerState]
 * 等纯数据。
 *
 * 这是**整个插件里唯一 import `com.jetbrains.plugins.webDeployment.*` 的文件**。
 *
 * ## 为什么隔离得这么严
 *
 * 这个包（`com.jetbrains.*` 而不是 `com.intellij.*`）是**产品内部实现**，不是平台 API：
 * 它没有文档、没有稳定性承诺。更实际的问题是它**只在 Ultimate 里存在**
 * （插件 `com.jetbrains.plugins.webDeployment` 依赖 `com.intellij.modules.ultimate`）。
 *
 * 所以调用方（`DeploymentToolsProvider`）必须先确认插件在，再走到这里；
 * 把引用集中在一个文件里，是为了让"插件不在时会加载哪些类"这件事可推理。
 *
 * ## 读到的 API 都是实测过的公开方法
 *
 * - `GroupedServersConfigManager.getFlattenedServers()` —— 当前项目视角下可用的服务器
 *   （应用级的那些所有项目共享，项目级的只属于本项目）
 * - `PublishConfig` —— **项目级**选项 + **按服务器**的 Mappings
 * - `WebServerConfig` / `Connectable` —— 服务器自身的属性
 *
 * 注意 `hasCredentials()` 不在 `WebServerConfig` 上，而在它派生出的 `Deployable`（实现 `Connectable`）上，
 * 所以要用 `Deployable.create(server, project)` 造一个再问。**这是纯工厂，不联网、不弹框**。
 */

internal fun publishConfigOf(project: Project): PublishConfig = PublishConfig.getInstance(project)

/**
 * 当前默认服务器/组的名字。没配过就是 `null` —— 那意味着"上传到默认服务器"和自动上传都没有目标。
 *
 * 注意 getter 叫 `getDefaultServerOrGroupName()` 而 setter 叫 `setDefaultGroupOrServerName(...)`，
 * 两边名字不对称，Kotlin 不会把它当属性用，所以这里显式调用 getter。
 */
internal fun currentDefaultServerOrGroupName(project: Project): String? =
    publishConfigOf(project).getDefaultServerOrGroupName()

/** 项目级的部署选项（**整个项目一套，所有服务器共用**）。 */
internal fun currentDeploymentOptions(project: Project): DeploymentOptionsState {
    val config = publishConfigOf(project)
    return DeploymentOptionsState(
        autoUpload = config.autoUploadState.name,
        overwriteAll = config.isOverwriteAll,
        deleteMissingItems = config.isDeleteMissingItems,
        preserveTimestamps = config.isPreserveTimestamps,
        confirmBeforeUploading = config.isConfirmBeforeUploading,
        confirmBeforeDeletion = config.isConfirmBeforeDeletion,
        autoUploadExternalChanges = config.isAutoUploadExternalChanges,
        remoteFilesAllowedToDisappearOnAutoupload = config.isRemoteFilesAllowedToDisappearOnAutoupload,
        excludeMask = config.excludeMask,
    )
}

/**
 * 当前项目视角下的**全部** deployment 服务器（应用级 + 项目级），每个都带上它在本项目下的 Mappings。
 *
 * 任何一步失败都不抛：这是一个纯读工具，"读不到"本身就值得作为结果报出去
 * （例如 [DeploymentServerState.validationIssue] 非空），而不是让整个调用变成错误。
 */
internal fun allDeploymentServers(project: Project): List<DeploymentServerState> {
    val config = publishConfigOf(project)
    val defaultName = config.getDefaultServerOrGroupName()
    // getFlattenedServers() / getPathMappings() 都是 Java 方法、没有 @NotNull（平台类型 `T!`）：
    // 它们**确实会返回 null**（例如没有配置 mapping 时返回 null 而不是空列表），
    // 而 `Result.getOrDefault` 会把这个值当成非空 → 抛 "getOrDefault(...) must not be null"。
    // 所以一律走 `getOrNull().orEmpty()`。
    val servers = runCatching { GroupedServersConfigManager.getInstance(project).flattenedServers }
        .getOrNull()
        .orEmpty()

    return servers.map { server -> server.toState(project, config, defaultName) }
}

/** 一个 `WebServerConfig` → 纯数据快照。 */
private fun WebServerConfig.toState(
    project: Project,
    config: PublishConfig,
    defaultServerOrGroupName: String?,
): DeploymentServerState {
    // getName() 在 Kotlin 里不是属性访问（编译器要求显式调用），且被标注为可空；
    // 名字是后续工具的入参，所以统一兜成空串——宁可给空也不能给 null。
    val serverName = getName().orEmpty()

    // 凭据判断要经过 Deployable —— hasCredentials() 定义在 Connectable 上，
    // WebServerConfig 本身并没有这个方法（它只实现 Mappable）。
    // 造不出来（理论上不该发生）就当"没有凭据"：宁可少报可连接，也不要谎报。
    val deployable = runCatching { Deployable.create(this, project) }.getOrNull()
    val transfer = runCatching { fileTransferConfig }.getOrNull()

    return DeploymentServerState(
        name = serverName,
        id = id.orEmpty(),
        projectLevel = isProjectLevel,
        accessType = runCatching { accessType.name }.getOrNull() ?: "UNKNOWN",
        // host / port / rootFolder 都在 FileTransferConfig 上，WebServerConfig 自己没有这些。
        host = transfer?.let { runCatching { it.host }.getOrNull() },
        port = transfer?.let { runCatching { it.port }.getOrDefault(0) } ?: 0,
        rootFolder = transfer?.let { runCatching { it.rootFolder }.getOrNull() },
        // needsTransfer() 不符合 getter 命名规则，不是 Kotlin 属性，必须显式调用。
        needsTransfer = runCatching { needsTransfer() }.getOrDefault(true),
        hasCredentials = deployable?.let { runCatching { it.hasCredentials() }.getOrDefault(false) } ?: false,
        validationIssue = runCatching { validateFast() }.getOrNull(),
        isDefault = serverName == defaultServerOrGroupName,
        mappings = runCatching { config.getPathMappings(serverName) }
            .getOrNull()
            .orEmpty()
            .map { it.toState() },
    )
}

/** 一条 `DeploymentPathMapping` → 纯数据。 */
private fun DeploymentPathMapping.toState(): DeploymentMappingState = DeploymentMappingState(
    // 这三个 getter 同样没有 @NotNull：字段可能为空，所以用 getOrNull() 兜底再 orEmpty()。
    localPath = runCatching { localPath }.getOrNull().orEmpty(),
    deployPath = runCatching { deployPath }.getOrNull().orEmpty(),
    webPath = runCatching { webPath }.getOrNull().orEmpty(),
)

// ---------------------------------------------------------------------------
// upload / download 共用的平台侧
// ---------------------------------------------------------------------------

/** 按名字找服务器；找不到就是 null（调用方负责给出候选列表）。 */
internal fun findDeploymentServer(project: Project, name: String): WebServerConfig? =
    runCatching { GroupedServersConfigManager.getInstance(project).findServer(name) }.getOrNull()

/**
 * 解析"这次操作哪个服务器"。返回 (server, 失败原因)，两者必有其一。
 *
 * 给了名字就按名字；没给就用默认。默认是**组**（多个服务器）时直接拒绝：
 * "一次 diff 多个服务器"要让每个服务器各返回一份结果，那是另一种返回形状，
 * 不该在这里悄悄取第一个糊弄过去。
 */
internal fun resolveTargetServer(project: Project, requested: String?): Pair<WebServerConfig?, String?> {
    if (requested != null) {
        val server = findDeploymentServer(project, requested)
            ?: return null to "No deployment server named \"$requested\" in this project. Existing: " +
                allDeploymentServers(project).map { it.name } + "."
        return server to null
    }

    val config = publishConfigOf(project)
    val defaultName = config.getDefaultServerOrGroupName()
        ?: return null to "No default server or group is configured, so there is nothing to target. " +
            "Pass `serverName` explicitly, or set a default in Settings | Build, Execution, Deployment | Deployment."

    val valid = runCatching { config.findValidDefaultTransferableServers() }.getOrNull().orEmpty()
    return when {
        valid.size == 1 -> valid.first() to null

        valid.isEmpty() -> null to "The default \"$defaultName\" is not usable: it is either a group without a " +
            "transferable member, or its configuration is invalid. Pass `serverName` explicitly."

        else -> null to "The default \"$defaultName\" is a group of ${valid.size} servers. Comparing a whole group " +
            "is not supported yet — pass `serverName` to pick one of them."
    }
}

/**
 * 建连接**之前**的本地预检。返回 null 表示可以连。
 *
 * 每一条都是"连下去只会更糟"的理由：
 * - 不传输的服务器（LOCAL 类型）—— 连了也没有文件可传
 * - `validateFast()` 报毛病 —— 平台自己也会把它从可用列表里剔掉
 * - **没有可用凭据** —— 这是最要命的一条：连下去会让 IDE 弹模态登录框
 *   （`AuthHelper.ensureAuthSpecified` / `PublishUtils.showLoginDialogAfterAuthFail`），
 *   而 MCP 路径上没人能点它，调用会连同其它要上 EDT 的工具一起卡死。
 *   `hasCredentials()` 是纯内存判断，不联网、不弹框，正好当这道闸。
 */
internal fun connectionPreflightFailure(project: Project, server: WebServerConfig): String? {
    val name = server.getName().orEmpty()

    if (!runCatching { server.needsTransfer() }.getOrDefault(true)) {
        val type = runCatching { server.accessType.name }.getOrNull() ?: "local"
        return "\"$name\" is a $type server: it does not transfer anything over the network, so there is nothing " +
            "to compare or sync."
    }

    runCatching { server.validateFast() }.getOrNull()?.let { issue ->
        return "\"$name\" is misconfigured: $issue"
    }

    val deployable = runCatching { Deployable.create(server, project) }.getOrNull()
    if (deployable == null || !runCatching { deployable.hasCredentials() }.getOrDefault(false)) {
        return "\"$name\" has no usable credentials. Connecting would make the IDE open a modal login dialog, " +
            "which nothing can answer on the MCP path — it would block this call. Fix the credentials in the IDE first."
    }

    return null
}

// ---------------------------------------------------------------------------
// upload_to_deployment / download_from_deployment 的平台侧
// ---------------------------------------------------------------------------

/** 任务结果的两种状态。[DeploymentTaskOutcome.state] 取这两个值。 */
internal const val DEPLOYMENT_STATE_COMPLETED = "completed"
internal const val DEPLOYMENT_STATE_IN_PROGRESS = "in_progress"

/** 默认等待预算（毫秒）。实测 18 文件约 1 秒、318 文件约 25 秒。 */
internal const val DEFAULT_DEPLOYMENT_WAIT_MS = 10_000L

/** 允许调用方把预算调到多少（上限）。再大也超不过 MCP 的会话耐心（约 15 秒）。 */
internal const val MAX_DEPLOYMENT_WAIT_MS = 12_000L

/** 一次 deployment 任务的结果（平台层产出）。带 [handle] 以便事后查询。 */
internal data class DeploymentTaskOutcome(
    /** 这一次任务的句柄——查询状态时用它，而不是用服务器名。 */
    val handle: String,
    /** `upload`（推到服务器）或 `download`（从服务器拉）。 */
    val kind: String,
    val serverName: String,
    /** 该方向的策略：上传是 `upload`/`mirror`；下载是 `skip-local`/`take-remote`。 */
    val mode: String,
    val state: String,
    val elapsedMs: Long,
    /** 已处理的操作数。已完成时等于 [totalProcessed]；进行中时来自平台实时计数（拿不到为 0）。 */
    val processed: Int,
    /** 预计总数，平台可能给不出（0 表示未知）。 */
    val total: Int,
    /** 平台 `doRun`/下载完成回调给的结论（false = 有失败或出错）。进行中时恒为 false。 */
    val succeeded: Boolean,
    val totalProcessed: Int,
    val failed: Int,
    /** 平台按操作类型记的原始计数（key 是它自己的 bundle key）。进行中时为空。 */
    val counters: Map<String, Int>,
    val warnings: List<String>,
)

/**
 * 把 [roots] 下的内容**上传**到 [server]，并在 [waitMs] 内尽量同步地等它结束。
 *
 * **走平台的 `TransferTask`**，而不是自己建立连接、自己解析远端路径：路径解析（服务器 root folder +
 * mapping 的 deploy path）与 vfs 的 FileSystem 缓存行为都由平台处理，自己复刻只会踩坑。
 *
 * ## 返回值
 *
 * - 在预算内结束 → [DEPLOYMENT_STATE_COMPLETED]，带平台自己的计数
 * - 预算用完还没结束 → [DEPLOYMENT_STATE_IN_PROGRESS]，带句柄与实时进度；**任务继续在 IDE 里跑**，
 *   AI 用 [deploymentTaskStatusOf] 拿句柄接着查
 * - 该服务器已有任务在跑 → 不启动第二个，**加入那一个**（结果里的 handle 就是它的）
 *
 * @param config 决定本次行为的 `PublishConfig`。调用方应传**副本**（`clone()`）并只改本次要覆盖的选项，
 *   这样"这一次用 mirror"这类策略不会写进用户的持久配置
 */
internal suspend fun uploadToServer(
    project: Project,
    server: WebServerConfig,
    config: PublishConfig,
    roots: List<String>,
    waitMs: Long,
): DeploymentTaskOutcome {
    val serverName = server.getName().orEmpty()
    val registry = project.service<DeploymentTaskRegistry>()
    val (slot, isOwner) = registry.acquireOrJoin(DeploymentTaskRegistry.KIND_UPLOAD, serverName, MODE_MIRROR)

    if (!isOwner) return awaitSlot(registry, slot, waitMs, joinedExisting = true)

    val files = roots.mapNotNull { path ->
        runCatching { LocalFileSystem.getInstance().findFileByPath(path) }.getOrNull()
    }
    if (files.isEmpty()) {
        finishSlot(slot, "\"$serverName\" has no readable local root directory, so nothing was uploaded.")
        return outcomeOf(registry, slot)
    }

    val deployable = runCatching { Deployable.create(server, project) }.getOrNull()
    if (deployable == null) {
        finishSlot(slot, "Could not build a deployable from \"$serverName\"; nothing was uploaded.")
        return outcomeOf(registry, slot)
    }

    val task = UploadToServerTask(
        project = project,
        config = config,
        deployable = deployable,
        title = "Upload to $serverName",
        files = files,
        slot = slot,
    )
    slot.progressProvider = { DeploymentProgress(task.processedSteps(), task.totalSteps()) }

    return try {
        task.queue()
        awaitSlot(registry, slot, waitMs, joinedExisting = false)
    } catch (t: Throwable) {
        finishSlot(slot, "The upload could not be started: ${t.message ?: t::class.java.simpleName}")
        outcomeOf(registry, slot)
    }
}

/**
 * 把远端的内容**下载**到 [roots] 对应的本地目录，并在 [waitMs] 内尽量同步地等它结束。
 *
 * 走官方的 [DownloadAction.download]。注意它内部用的是 `ProgressManager.run()`（**阻塞调用线程**），
 * 不是上传那边的 `queue()`——所以这里**必须**放到后台线程上去跑，否则会把协程的线程占住。
 *
 * @param overwriteLocal 本地已有同名文件时是否被远端覆盖。`false`（默认）= 保留本地，较安全；
 *   `true` = 以远端为准。本地这一侧要不要被覆盖**没有"问用户"的选项**（IDE 默认会弹框，
 *   而 MCP 路径上没人能回答），所以只能在这里替用户定。
 */
internal suspend fun downloadFromServer(
    project: Project,
    server: WebServerConfig,
    roots: List<String>,
    overwriteLocal: Boolean,
    waitMs: Long,
): DeploymentTaskOutcome {
    val serverName = server.getName().orEmpty()
    val mode = if (overwriteLocal) MODE_TAKE_REMOTE else MODE_SKIP_LOCAL
    val registry = project.service<DeploymentTaskRegistry>()
    val (slot, isOwner) = registry.acquireOrJoin(DeploymentTaskRegistry.KIND_DOWNLOAD, serverName, mode)

    if (!isOwner) return awaitSlot(registry, slot, waitMs, joinedExisting = true)

    val localFiles = roots.mapNotNull { path ->
        runCatching { LocalFileSystem.getInstance().findFileByPath(path) }.getOrNull()
    }
    if (localFiles.isEmpty()) {
        finishSlot(slot, "\"$serverName\" has no readable local root directory, so nothing was downloaded.")
        return outcomeOf(registry, slot)
    }

    val deployable = runCatching { Deployable.create(server, project) }.getOrNull()
    if (deployable == null) {
        finishSlot(slot, "Could not build a deployable from \"$serverName\"; nothing was downloaded.")
        return outcomeOf(registry, slot)
    }

    // Kotlin `object`：不用写 INSTANCE，直接用类名本身。
    val strategy: IgnoreOverwritingStrategy = if (overwriteLocal) AlwaysOverwrite else AlwaysSkip

    // 平台的 download 内部会调 `saveDocumentsSilently`（要写动作）+ `ProgressManager.run()`（阻塞），
    // 所以它**必须在 EDT 上调用**。代价是这期间 EDT 被占住——这与我们在别处记录过的"模态框卡 EDT"
    // 是同一类风险，只是这里的阻塞时长等于下载时长，且我们用预算上限兜底。
    val connection = withContext(Dispatchers.EDT) {
        val deployableOnEdt = runCatching { Deployable.create(server, project) }.getOrNull()
            ?: return@withContext null
        runCatching {
            RemoteConnectionManager.getInstance().openConnection(
                ConnectionOwnerFactory.createConnectionOwner(project),
                "deployment download",
                deployableOnEdt,
                FileTransferConfig.Origin.Default,
                null,
                EmptyProgressIndicator(),
            )
        }.getOrNull()
    } ?: run {
        finishSlot(slot, "Could not open a connection to \"$serverName\"; nothing was downloaded.")
        return outcomeOf(registry, slot)
    }

    // EDT 上调用平台的阻塞下载；用 try/finally 保证连接一定释放。
    val failure = withContext(Dispatchers.EDT) {
        try {
            val remoteRoot = runCatching { connection.resolveRoot() }.getOrNull()
            if (remoteRoot == null) {
                "Could not locate the deployment root on \"$serverName\"."
            } else {
                // **平台的 download 收的是"远端的具体文件"，不是目录**：
                // 它把每个远端 FileObject 反算成本地路径（`UploadFilesAction.getLocalRoots`），
                // 再逐个 `processIncoming` 拉下来。传目录进去会算出那个目录的本地路径，
                // 而本地已经有它了 —— 一个字节都不会传（实测：回调触发了、零传输）。
                // 所以这里必须先递归列出远端目录下的文件，再交给它。
                val remoteFiles = runCatching { collectRemoteFiles(remoteRoot, MAX_REMOTE_FILES) }
                    .getOrDefault(emptyList())
                if (remoteFiles.isEmpty()) {
                    "There is nothing on \"$serverName\" to download."
                } else {
                    // ⚠️ 未解决：这里两个数组的语义还没搞清。
                    //
                    // 已确认的事实：
                    //  1) 必须在 EDT 上调（内部 saveDocumentsSilently 要写动作）
                    //  2) 收的是**具体文件**，不是目录（传目录 → 零传输）
                    //  3) remoteFiles 用来调 getLocalRoots(...) 反推"扫哪个本地目录"
                    //  4) 平台打印 "No files or folders found to process" ⇒ 它筛完没剩东西
                    //
                    // 猜测（未验证）：localFiles 应该是这批远端文件对应的**本地目标**，
                    // 空数组 = 没有下载目标 → 正好对得上 "not found to process"。
                    // 但反算本地路径该用哪个 API（getNearestMappingDeploy2Local 返回的是
                    // mapping 不是路径）我没验证过，所以先不瞎填。
                    val localTargets = emptyList<VirtualFile>()
                    DownloadAction.download(
                        project,
                        deployable,
                        connection,
                        remoteFiles.toTypedArray(),
                        localTargets.toTypedArray(),
                        { slot.result = completedOutcome(slot); slot.finished.complete(Unit) },
                        strategy,
                    )
                    // 正常返回但回调没触发：兜底，别让句柄永远悬着。
                    if (slot.finished.isCompleted) null else "The download ended without reporting a result."
                }
            }
        } catch (t: Throwable) {
            "The download failed: ${t.message ?: t::class.java.simpleName}"
        } finally {
            runCatching { connection.release() }
        }
    }

    if (failure != null) finishSlot(slot, failure)

    // 等完成信号或预算用尽，两者谁先到算谁。
    val finished = withTimeoutOrNull(waitMs.coerceIn(0, MAX_DEPLOYMENT_WAIT_MS)) { slot.finished.await() }
    if (finished != null) return outcomeOf(registry, slot)

    return inProgressOutcome(
        registry = registry,
        slot = slot,
        joinedExisting = false,
        extra = "A download reports no per-file progress, so there is nothing to show until it finishes.",
    )
}

/**
 * 按句柄查一次 deployment 任务的进度。**纯只读**：不会启动任何任务。
 *
 * 查不到返回 null —— 句柄不存在，或它已被"保留最近 20 条"的清理策略丢掉了。
 */
internal fun deploymentTaskStatusOf(project: Project, handle: String): DeploymentTaskOutcome? {
    val registry = project.service<DeploymentTaskRegistry>()
    val slot = registry.lookup(handle) ?: return null
    return outcomeOf(registry, slot)
}

/** 递归列远端目录下的**文件**（不含目录本身），上限 [limit] 个。 */
private fun collectRemoteFiles(root: FileObject, limit: Int): List<FileObject> {
    if (limit <= 0) return emptyList()
    val out = mutableListOf<FileObject>()

    fun walk(dir: FileObject) {
        if (out.size >= limit) return
        val children = runCatching { DeploymentPathUtils.getChildren(dir) }.getOrNull() ?: return
        for (child in children) {
            if (out.size >= limit) return
            when (runCatching { child.type }.getOrNull()) {
                FileType.FOLDER -> walk(child)
                FileType.FILE -> out.add(child)
                else -> Unit
            }
        }
    }

    walk(root)
    return out
}

/** 远端一次最多列多少文件（防御大目录把收集阶段拖死）。 */
private const val MAX_REMOTE_FILES = 2_000

/** 把登记表里的一个任务转成结果快照。 */
private fun outcomeOf(
    registry: DeploymentTaskRegistry,
    slot: DeploymentTaskRegistry.Slot,
): DeploymentTaskOutcome {
    val elapsed = registry.elapsedMsOf(slot)
    val done = slot.result
    if (done != null) return done.copy(elapsedMs = elapsed)
    return inProgressOutcome(registry, slot, joinedExisting = false, extra = null, elapsedMs = elapsed)
}

/** "还在进行中"的快照，附带**下一步该做什么**的明确指引。 */
private fun inProgressOutcome(
    registry: DeploymentTaskRegistry,
    slot: DeploymentTaskRegistry.Slot,
    joinedExisting: Boolean,
    extra: String?,
    elapsedMs: Long = registry.elapsedMsOf(slot),
): DeploymentTaskOutcome {
    val progress = registry.progressOf(slot)
    val origin = if (joinedExisting) {
        "A ${slot.kind} started by an earlier call is still running"
    } else {
        "The ${slot.kind} is still running in the IDE"
    }
    return DeploymentTaskOutcome(
        handle = slot.handle,
        kind = slot.kind,
        serverName = slot.serverName,
        mode = slot.mode,
        state = DEPLOYMENT_STATE_IN_PROGRESS,
        elapsedMs = elapsedMs,
        processed = progress.processed,
        total = progress.total,
        succeeded = false,
        totalProcessed = 0,
        failed = 0,
        counters = emptyMap(),
        warnings = listOf(
            "${slot.kind.replaceFirstChar { it.uppercase() }} to \"${slot.serverName}\" (mode=${slot.mode}) is still " +
                "in progress — $origin (${elapsedMs}ms so far" +
                (if (progress.total > 0) ", ${progress.processed}/${progress.total} operations" else "") +
                "). It keeps going after this call returns. Query it with handle \"${slot.handle}\"; " +
                "do NOT start it again, that would not report progress but run yet another transfer." +
                (extra?.let { " $it" } ?: ""),
        ),
    )
}

/** 在预算内等它结束；等不到就返回"进行中"（任务继续在 IDE 里跑）。 */
private suspend fun awaitSlot(
    registry: DeploymentTaskRegistry,
    slot: DeploymentTaskRegistry.Slot,
    waitMs: Long,
    joinedExisting: Boolean,
): DeploymentTaskOutcome = withContext(Dispatchers.IO) {
    val finished = withTimeoutOrNull(waitMs.coerceIn(0, MAX_DEPLOYMENT_WAIT_MS)) { slot.finished.await() }
    if (finished != null) return@withContext outcomeOf(registry, slot)
    inProgressOutcome(registry, slot, joinedExisting, extra = null)
}

/** 下载完成时由平台的回调写这个（平台的 download 不暴露计数器，所以全是 0 / 空）。 */
private fun completedOutcome(slot: DeploymentTaskRegistry.Slot): DeploymentTaskOutcome = DeploymentTaskOutcome(
    handle = slot.handle,
    kind = slot.kind,
    serverName = slot.serverName,
    mode = slot.mode,
    state = DEPLOYMENT_STATE_COMPLETED,
    elapsedMs = 0, // 由调用方用 registry 算实际值
    processed = 0,
    total = 0,
    succeeded = true,
    totalProcessed = 0,
    failed = 0,
    counters = emptyMap(),
    warnings = emptyList(),
)

/** 没跑起来 / 出错时也要让登记项落地，这样句柄不会永远悬着。 */
private fun finishSlot(slot: DeploymentTaskRegistry.Slot, reason: String) {
    slot.result = completedOutcome(slot).copy(succeeded = false, warnings = listOf(reason))
    slot.progressProvider = null
    slot.finished.complete(Unit)
}

/** 策略取值（上传 / 下载各一套）。 */
internal const val MODE_UPLOAD = "upload"
internal const val MODE_MIRROR = "mirror"
internal const val MODE_SKIP_LOCAL = "skip-local"
internal const val MODE_TAKE_REMOTE = "take-remote"

/**
 * 执行一次上传的 `TransferTask`。
 *
 * 形状照着官方 `PublishActionUtil.uploadToDefaultServer` 内部那个匿名子类来（它就是"上传到某个
 * `WebServerConfig`"的现成实现，只是没公开）：
 *
 * - 操作列表用公开的 [PublishActionUtil.scanFiles]（官方也用它）
 * - 那 4 个 boolean 与官方构造时传的值**完全一致**（语义没公开文档，照抄最安全）
 * - 覆写 [run] 是为了接住 `doRun` 的结果与计数器，官方那个版本只 set 一个 Promise
 */
private class UploadToServerTask(
    project: Project,
    config: PublishConfig,
    deployable: Deployable,
    title: String,
    private val files: List<VirtualFile>,
    private val slot: DeploymentTaskRegistry.Slot,
) : TransferTask.ListBased(
    project,
    ConnectionOwnerFactory.createConnectionOwner(project),
    true,
    config,
    deployable,
    title,
    true,
    true,
    true,
    ProjectDeploymentRevisionTracker.getInstance(project),
) {

    /** 已处理的操作数（轮询进度用）。 */
    fun processedSteps(): Int = runCatching { myTotalProcessed.get() }.getOrDefault(0)

    /**
     * 预计总数。`getTotalStepsNumber()` 是 protected 的（我们继承自 `ListBased`），
     * 平台在收集阶段结束前可能还没算准，给不出就报 0。
     */
    fun totalSteps(): Int = runCatching { getTotalStepsNumber() }.getOrDefault(0)

    override fun buildOperationsList(context: ExecutionContext): ResultWithErrors =
        PublishActionUtil.scanFiles(context, files)

    override fun run(indicator: ProgressIndicator) {
        val ok = doRun(indicator)
        val processed = runCatching { myTotalProcessed.get() }.getOrDefault(0)
        slot.result = DeploymentTaskOutcome(
            handle = slot.handle,
            kind = slot.kind,
            serverName = slot.serverName,
            mode = slot.mode,
            state = DEPLOYMENT_STATE_COMPLETED,
            elapsedMs = 0, // 由调用方用 registry 算实际值
            processed = processed,
            total = processed,
            succeeded = ok,
            totalProcessed = processed,
            failed = runCatching { myFailed.get() }.getOrDefault(0),
            counters = runCatching { myProcessedCounters.mapValues { it.value.get() } }.getOrDefault(emptyMap()),
            warnings = emptyList(),
        )
        slot.progressProvider = null
        slot.finished.complete(Unit)
    }
}
