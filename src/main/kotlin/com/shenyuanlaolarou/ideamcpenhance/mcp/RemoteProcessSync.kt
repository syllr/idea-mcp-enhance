package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.project.Project
import com.intellij.ssh.SftpChannelConfig
import com.intellij.ssh.SftpProgressTracker
import com.intellij.ssh.SshConnectionService
import java.io.File

/**
 * 部署同步：用 IDE 自己的 SFTP 通道把本地目录传到远端。
 *
 * 为什么不用 `UploadBeforeRunTaskProvider`：它要求 `DataContext` 里有 project、
 * `UploadBeforeRunTask` 的 roots 还要 XML 反序列化才能填，实测在 MCP 调用上下文里
 * 反复返回 false。SFTP 通道是同一套 SSH 凭据，直接可控。
 *
 * `uploadFileOrDir` 的参数是「远端父目录 + 子路径」，不是「远端完整路径」：
 * 例如 `/data` + `app` → `/data/app`。拼错会静默不传。
 *
 * 语义与 `remote_upload` 的默认模式一致：覆盖 / 新增，不删远端多余文件。
 */
internal object RemoteProcessSync {

    /** 进度输出间隔：文件多时逐条打印会刷屏。 */
    private const val PROGRESS_INTERVAL_MS = 5000L

    /**
     * @param excludes 相对本地根目录的路径前缀 / 精确文件。
     * @param onProgress 文件级进度回调（已经带上 `[同步] n/total path` 的文字）。
     */
    fun upload(
        project: Project,
        serverName: String,
        localPath: String,
        remotePath: String,
        excludes: List<String>,
        onProgress: ((String) -> Unit)? = null,
    ) {
        val config = RemoteSshClient.sshConfig(project, serverName)
            ?: throw IllegalStateException("IDE 的 SSH 配置里没有 \"$serverName\"。")
        val root = File(resolveLocalPath(project.basePath, localPath))
        if (!root.isDirectory) throw IllegalStateException("本地目录不存在：$localPath")

        val normalizedRemote = remotePath.trim().trimEnd('/').ifEmpty { "/" }
        val parent = normalizedRemote.substringBeforeLast('/', "/").ifEmpty { "/" }
        val name = normalizedRemote.substringAfterLast('/')

        val total = countFiles(root, root, excludes)
        var copied = 0
        var lastReportAt = System.currentTimeMillis()
        val tracker = object : SftpProgressTracker {
            override val isCanceled: Boolean = false

            override fun onFileCopied(file: File) {
                copied += 1
                val now = System.currentTimeMillis()
                // 每 5 秒报一次数量；不逐文件打印。
                if (now - lastReportAt >= PROGRESS_INTERVAL_MS) {
                    lastReportAt = now
                    onProgress?.invoke("[同步] $copied/$total")
                }
            }

            override fun onBytesTransferred(count: Long) = Unit
        }

        // 同 RemoteSshClient.run：sshj 会检查调用线程，整个 SFTP 过程放到固定线程。
        RemoteSshClient.execExecutor.submit<Unit> {
            val channel = SshConnectionService.instance.sftp(
                RemoteSshClient.sessionConfigOf(config),
                SftpChannelConfig(),
            )
            try {
                onProgress?.invoke("[同步] 共 $total 个文件 → $normalizedRemote")
                channel.uploadFileOrDir(
                    root,
                    parent,
                    name,
                    tracker,
                ) { file ->
                    val relative = runCatching {
                        file.relativeTo(root).invariantSeparatorsPath
                    }.getOrNull()
                    relative.isNullOrEmpty() || !isExcluded(relative, excludes)
                }
            } finally {
                runCatching { channel.close() }
                // 完成（或失败）时补一次最终数量，5 秒内同步完也能看到。
                onProgress?.invoke("[同步] $copied/$total")
            }
        }.get()
    }

    private fun countFiles(root: File, directory: File, excludes: List<String>): Int {
        var count = 0
        directory.listFiles().orEmpty().forEach { child ->
            val relative = runCatching { child.relativeTo(root).invariantSeparatorsPath }.getOrNull()
                ?: return@forEach
            if (relative.isEmpty() || isExcluded(relative, excludes)) return@forEach
            count += if (child.isDirectory) countFiles(root, child, excludes) else 1
        }
        return count
    }

    /** 相对路径是否命中排除项：`p === pattern` 或 `p` 以 `pattern/` 开头。 */
    internal fun isExcluded(relative: String, patterns: List<String>): Boolean =
        patterns.any { pattern ->
            val trimmed = pattern.trim().trimEnd('/')
            trimmed.isNotEmpty() && (relative == trimmed || relative.startsWith("$trimmed/"))
        }
}
