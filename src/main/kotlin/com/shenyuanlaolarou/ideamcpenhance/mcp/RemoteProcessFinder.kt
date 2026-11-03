package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.project.Project

/**
 * 按 `command + workingDirectory` 找远端进程。
 *
 * 为什么不只信 pid：pid 可能被复用、也可能和 `nohup` 的包装 shell 对不上。
 * 启动时记的 pid 仍然优先（`ps -p` 校验 command），对不上时再用
 * `ps -axo pid=,command=` 找候选、用 `lsof` 的 cwd 收窄。
 */
internal object RemoteProcessFinder {

    /**
     * @return 匹配的 pid；找不到返回 null。
     */
    fun find(
        project: Project,
        server: String,
        command: String,
        workingDirectory: String?,
    ): Long? {
        val needle = sanitize(command)
        if (needle.isEmpty()) return null

        if (workingDirectory.isNullOrBlank()) {
            val output = RemoteSshClient.run(
                project,
                server,
                "ps -axo pid=,command= | grep -F ${quote(needle)} | grep -v grep || true",
            )
            return output.lineSequence()
                .mapNotNull { line -> line.trim().substringBefore(' ').toLongOrNull() }
                .firstOrNull()
        }

        // 一次 SSH 完成「ps 找候选 → 逐个 lsof 比 cwd → 输出匹配的 pid」，
        // 避免每条命令一条连接。
        val script = """
            target=${quote(workingDirectory)}
            target=${'$'}(cd "${'$'}target" 2>/dev/null && pwd -P || echo "${'$'}target")
            ps -axo pid=,command= | grep -F ${quote(needle)} | grep -v grep | while read pid rest; do
              cwd=${'$'}(lsof -p "${'$'}pid" -a -d cwd -Fn 2>/dev/null | tail -1 | sed 's/^n//')
              [ "${'$'}cwd" = "${'$'}target" ] && echo "${'$'}pid"
            done
        """.trimIndent()
        val output = RemoteSshClient.run(project, server, script)
        return output.lineSequence()
            .mapNotNull { line -> line.trim().toLongOrNull() }
            .firstOrNull()
    }

    /** `ps` 输出里的 command 不带引号；去掉引号后再做固定字符串匹配。 */
    private fun sanitize(command: String): String =
        command.replace("'", "").replace("\"", "").trim()

    private fun cwdOf(project: Project, server: String, pid: Long): String? {
        val output = RemoteSshClient.run(
            project,
            server,
            "lsof -p $pid -a -d cwd -Fn 2>/dev/null | tail -1 | sed 's/^n//' || true",
        )
        return output.trim().takeIf { it.isNotEmpty() }
    }

    /** `cd <dir> && pwd -P`：展开符号链接，和 `lsof` 的 cwd 对齐（`/tmp` → `/private/tmp`）。 */
    private fun normalizePath(project: Project, server: String, path: String): String {
        val output = RemoteSshClient.run(
            project,
            server,
            "cd ${quote(path)} 2>/dev/null && pwd -P || echo ${quote(path)}",
        )
        return output.trim()
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
