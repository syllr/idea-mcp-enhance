package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * 运行日志的**平台接线层**：建文件、挂监听、盖章、扫历史。
 *
 * ## 为什么必须在进程启动的那一刻接上
 *
 * 平台**没有**公开 API 能事后读控制台内容：`RunContentDescriptor.getExecutionConsole()` 是公开的，
 * 但它给出的 `ConsoleView` 只有 `getContentSize()`；能取文本的 `ConsoleViewImpl` 在
 * `com.intellij.execution.impl` 包里 —— 本仓库禁用那个包（见 `AGENTS.md` 的 HARD RULES）。
 *
 * 所以只能在进程启动时开始抓。抓点就是现有的"事件盖章"机制：
 * `ExecutionListener.processStarting(executorId, env, handler)`。
 *
 * 字节码核实（2026.2.3）：`ExecutionManagerImpl.doStartRunProfile$lambda$1$0` 里
 * `processStarting(...)` 在 offset 302，紧跟着 offset 309 就是 `processHandler.startNotify()` ——
 * 事件在进程吐第一个字**之前**发出，所以挂上 listener 不会漏首行。
 *
 * ## 覆盖不到的情况（见 `DESIGN.md` §7.5）
 *
 * - 终端形态的 Shell（`EXECUTE_IN_TERMINAL=true`）：平台直接 `return null`，没有 ProcessHandler
 * - before-launch 的 Make / Build 步骤：平台自己的 handler，不发 `ExecutionListener` 事件
 * - 插件启用之前就启动的进程：监听是项目打开时挂的
 */

/** 盖在 [ProcessHandler] 上的日志戳：日志文件在哪、那次运行什么时候开始。 */
internal data class RunLogStamp(
    val logFile: String,
    val startedAtMillis: Long,
)

/** 戳挂在平台自己的对象上 —— 不建"executionId → 日志"的内存表（见 `DESIGN.md` §3.4 的同款理由）。 */
private val RUN_LOG_STAMP: Key<RunLogStamp> =
    Key.create("com.shenyuanlaolarou.ideamcpenhance.runLogStamp")

/** 把日志戳写进进程句柄。 */
internal fun stampRunLog(handler: ProcessHandler, stamp: RunLogStamp) {
    handler.putUserData(RUN_LOG_STAMP, stamp)
}

/** 读回戳；没有戳（不是我们见过的启动）时返回 null。 */
internal fun ProcessHandler.runLogStamp(): RunLogStamp? = getUserData(RUN_LOG_STAMP)

/**
 * 开始采集一次运行的输出。
 *
 * 返回 null = 没采到（建文件失败）。此时**不影响运行本身**，只是这次没有日志可看。
 */
internal fun startRunLogCapture(
    project: Project,
    configurationName: String,
    typeId: String?,
    handler: ProcessHandler,
): RunLogStamp? {
    val key = runLogKey(projectLogScope(project), configurationName)
    val startedAt = System.currentTimeMillis()
    val fileName = runLogFileName(key, startedAt, UUID.randomUUID().toString().take(4))
    val directory = PathManager.getTempDir()
    val logFile = directory.resolve(fileName)
    // 建不出来就放弃采集：磁盘满 / 权限问题不该让用户的运行失败。
    // 目录不保证已存在（`getTempDir()` 只是 system/tmp 的路径，不一定被建过）。
    val created = runCatching {
        Files.createDirectories(directory)
        Files.createFile(logFile)
    }
    if (created.isFailure) {
        Logger.getInstance("com.shenyuanlaolarou.ideamcpenhance.RunLog")
            .warn("Could not create run log $logFile: ${created.exceptionOrNull()?.message}")
        return null
    }
    val sidecar = directory.resolve(runLogSidecarFileName(fileName))
    handler.addProcessListener(
        RunLogWriter(
            logFile = logFile,
            sidecarFile = sidecar,
            configurationName = configurationName,
            typeId = typeId,
            startedAtMillis = startedAt,
        ),
    )
    return RunLogStamp(logFile = logFile.toString(), startedAtMillis = startedAt)
}

/**
 * 列出某个配置的历史运行，**按开始时间倒序**（最近的一次在最前）。
 *
 * 只认"日志文件还在"的：文件被清掉了就不算一条历史（它连日志都没有了）。
 */
internal fun listRunLogs(project: Project, configurationName: String): List<RunLogEntry> {
    val key = runLogKey(projectLogScope(project), configurationName)
    val directory = PathManager.getTempDir()
    val names = runCatching {
        Files.list(directory).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
    }.getOrNull() ?: return emptyList()

    return names.mapNotNull { name ->
        val parsed = parseRunLogFileName(name) ?: return@mapNotNull null
        if (parsed.key != key) return@mapNotNull null
        val sidecar = readSidecar(directory.resolve(runLogSidecarFileName(name)))
        RunLogEntry(
            startedAtMillis = parsed.startedAtMillis,
            logFile = directory.resolve(name).toString(),
            endedAtMillis = sidecar?.endedAtMillis,
            pid = sidecar?.pid,
            exitCode = sidecar?.exitCode,
        )
    }.sortedByDescending { it.startedAtMillis }
}

/**
 * 清理过期日志（IDE 启动时跑一次）。
 *
 * 删的是"超过保留期"的日志文件与 sidecar。**不删**还在被进程写的文件 ——
 * 刚启动的进程写的是刚建的文件，本来就不过期。
 */
internal fun cleanupExpiredRunLogs() {
    val directory = PathManager.getTempDir()
    val now = System.currentTimeMillis()
    runCatching {
        Files.list(directory).use { stream ->
            stream.forEach { file ->
                val name = file.fileName.toString()
                if (!name.startsWith(RUN_LOG_FILE_PREFIX)) return@forEach
                if (!name.endsWith(RUN_LOG_FILE_SUFFIX) && !name.endsWith(RUN_LOG_SIDECAR_SUFFIX)) return@forEach
                val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return@forEach
                if (now - modified > RUN_LOG_RETENTION_MS) {
                    runCatching { Files.deleteIfExists(file) }
                }
            }
        }
    }
}

/**
 * 改名时把历史日志的键跟着迁移。
 *
 * 日志文件名里的键来自配置名（见 [runLogKey]），改名之后旧键的文件就再也扫不到了 ——
 * 历史会"凭空消失"。所以改名工具 MUST 调这个，把旧键开头的文件整体改成新键。
 *
 * @return 迁移的文件数
 */
internal fun renameRunLogs(project: Project, oldName: String, newName: String): Int {
    val scope = projectLogScope(project)
    val oldKey = runLogKey(scope, oldName)
    val newKey = runLogKey(scope, newName)
    if (oldKey == newKey) return 0
    val directory = PathManager.getTempDir()
    val oldPrefix = "$RUN_LOG_FILE_PREFIX${oldKey}_"
    val names = runCatching {
        Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList() }
    }.getOrNull() ?: return 0
    var moved = 0
    for (name in names) {
        if (!name.startsWith(oldPrefix)) continue
        if (!name.endsWith(RUN_LOG_FILE_SUFFIX) && !name.endsWith(RUN_LOG_SIDECAR_SUFFIX)) continue
        val target = directory.resolve("$RUN_LOG_FILE_PREFIX${newKey}_" + name.removePrefix(oldPrefix))
        if (runCatching { Files.move(directory.resolve(name), target) }.isSuccess) moved++
    }
    return moved
}

/** 项目在日志键里的作用域。用项目路径，区分不同项目里同名的配置。 */
private fun projectLogScope(project: Project): String = project.basePath ?: project.name

/** sidecar 里存的东西：进程退出时刻、进程号、退出码。 */
private data class RunLogSidecar(
    val endedAtMillis: Long?,
    val pid: Long?,
    val exitCode: Int?,
)

/** 读 sidecar；文件不存在 / 坏了都当"没有富化信息"。 */
private fun readSidecar(sidecarFile: Path): RunLogSidecar? {
    val text = runCatching { Files.readString(sidecarFile) }.getOrNull() ?: return null
    val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    return RunLogSidecar(
        endedAtMillis = obj.longOrNull("endedAt"),
        pid = obj.longOrNull("pid"),
        exitCode = obj.intOrNull("exitCode"),
    )
}

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

/**
 * 把进程的输出写进日志文件，退出时补一个 sidecar。
 *
 * 直接实现 [ProcessListener]（2026.2 起 `ProcessAdapter` 已废弃，接口方法都有默认实现）。
 */
private class RunLogWriter(
    private val logFile: Path,
    private val sidecarFile: Path,
    private val configurationName: String,
    private val typeId: String?,
    private val startedAtMillis: Long,
) : ProcessListener {

    /** 写入是同步的，可能来自任意线程 —— 用锁把"追加 + 计数"变成一个原子动作。 */
    private val lock = Any()
    private var writtenBytes = 0L
    private var truncated = false

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        val text = event.text ?: return
        if (text.isEmpty()) return
        append(if (outputType == ProcessOutputTypes.STDERR) markStderr(text) else text)
    }

    override fun processTerminated(event: ProcessEvent) {
        val endedAt = System.currentTimeMillis()
        val exitCode = event.exitCode
        val pid = processPidOrNull(event.processHandler)
        val payload = buildJsonObject {
            put("configurationName", configurationName)
            put("type", typeId)
            put("startedAt", startedAtMillis)
            put("endedAt", endedAt)
            put("pid", pid)
            put("exitCode", exitCode)
            put("logFile", logFile.toString())
        }
        runCatching {
            Files.writeString(
                sidecarFile,
                payload.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
    }

    /** 追加一段输出；超过上限就停写并记下"被截断"。 */
    private fun append(payload: String) {
        synchronized(lock) {
            if (truncated) return
            val remaining = RUN_LOG_SIZE_LIMIT_BYTES - writtenBytes
            if (remaining <= 0) {
                truncated = true
                return
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val slice = if (bytes.size <= remaining) bytes else bytes.copyOf(remaining.toInt())
            writtenBytes += slice.size
            if (slice.size < bytes.size) truncated = true
            runCatching {
                Files.write(logFile, slice, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }
}

/**
 * 给 stderr 的每一行加个标记，内容本身不改。
 *
 * 这样 agent 读日志时能一眼看出哪几行是错误输出 —— 而不用去猜。
 */
private fun markStderr(text: String): String {
    val builder = StringBuilder(text.length + 16)
    var atLineStart = true
    for (ch in text) {
        if (atLineStart) {
            builder.append("[stderr] ")
            atLineStart = false
        }
        builder.append(ch)
        if (ch == '\n') atLineStart = true
    }
    return builder.toString()
}
