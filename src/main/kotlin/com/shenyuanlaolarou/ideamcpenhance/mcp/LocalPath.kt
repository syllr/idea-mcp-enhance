package com.shenyuanlaolarou.ideamcpenhance.mcp

import java.nio.file.Path

/**
 * 项目内路径（`localPath`）：必须是相对项目根目录的路径。
 *
 * 判定**不能用 `Path.isAbsolute`** —— 它按本机文件系统的语义算：Windows 上 `/srv/app`
 * 不算绝对路径，macOS 上 `C:\app` 也不算。而 `hosts.json` 跟着仓库走，会被 Windows /
 * macOS / Linux 三种机器读到，所以这里按「三种平台的绝对写法」逐个排除：
 * POSIX 根 `/x`、Windows 盘符 `C:x`、UNC 共享 `\\server\share`、家目录 `~`。
 * 分隔符只认 `/`：`src\app` 直接报错，不做兼容 —— 仓库里只存一种写法。
 */
internal fun requireRelativeLocalPath(name: String, localPath: String) {
    val path = localPath.trim()
    if (path.contains('\\')) {
        throw DeploymentConfigException(
            "deployment \"$name\" 的 localPath 必须使用 \"/\"，不能用 \"\\\"：$localPath",
        )
    }
    if (path.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(path)) {
        throw DeploymentConfigException(
            "deployment \"$name\" 的 localPath 必须是相对项目根目录的路径，不能是绝对路径：$localPath",
        )
    }
    if (path == "~" || path.startsWith("~/")) {
        throw DeploymentConfigException(
            "deployment \"$name\" 的 localPath 必须是相对项目根目录的路径，不能用 ~ 表示家目录：$localPath",
        )
    }
    if (path.split('/').contains("..")) {
        throw DeploymentConfigException(
            "deployment \"$name\" 的 localPath 不能包含 \"..\"，必须落在项目根目录内：$localPath",
        )
    }
}

/**
 * 远端 `deployPath` 必须是 POSIX 绝对路径。
 *
 * 部署目标只有 Linux / macOS，和本机是什么系统无关，所以同样不能用 `Path.isAbsolute`：
 * Windows 上 `/srv/app` 会被判成「相对于当前盘符」，合法配置会被误杀。相对路径则会被
 * SFTP 当成 SSH 用户的家目录，文件落到 `/home/<user>/...`，启动命令仍在找原目录。
 */
internal fun requireAbsoluteDeployPath(name: String, deployPath: String) {
    requireAbsoluteRemotePath("deployment \"$name\" 的 deployPath", deployPath)
}

/**
 * 远端路径（deployPath / workingDirectory）的统一规则：POSIX 绝对、`/` 分隔、不含 `..`。
 */
internal fun requireAbsoluteRemotePath(label: String, value: String) {
    val path = value.trim()
    if (!path.startsWith("/")) {
        throw DeploymentConfigException("$label 必须是远端绝对路径（以 / 开头）：$value")
    }
    if (path.contains('\\')) {
        throw DeploymentConfigException("$label 只能用 / 分隔（远端是 Linux / macOS）：$value")
    }
    if (path == "/" || path.split('/').contains("..")) {
        throw DeploymentConfigException("$label 不能是根目录，也不能包含 \"..\"：$value")
    }
}

/**
 * 相对路径按项目根解析成绝对路径；已经是绝对路径的原样返回。
 *
 * 只给内部写 IDE Deployment / 读本地文件用，不改变 hosts.json 里存的值。
 */
internal fun resolveLocalPath(basePath: String?, localPath: String): String {
    val path = Path.of(localPath.trim())
    if (path.isAbsolute) return path.normalize().toString()
    if (basePath.isNullOrBlank()) {
        throw DeploymentConfigException("项目没有 basePath，无法解析相对路径：$localPath")
    }
    return Path.of(basePath).resolve(path).normalize().toString()
}

/** Windows 盘符（`C:` / `C:/...`）——三种平台里只有 Windows 认，但它一定不是项目内相对路径。 */
private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
