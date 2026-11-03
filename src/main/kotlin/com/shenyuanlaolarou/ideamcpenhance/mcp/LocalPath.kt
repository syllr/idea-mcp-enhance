package com.shenyuanlaolarou.ideamcpenhance.mcp

import java.nio.file.Path

/**
 * 项目级 `hosts.json` 的 `localPath` 只能是相对项目根目录的路径。
 *
 * 绝对路径跟着某一台电脑的用户名走，换台机器就传错目录，所以在入口处直接拒绝。
 */
internal fun requireRelativeLocalPath(name: String, localPath: String) {
    if (Path.of(localPath).isAbsolute) {
        throw DeploymentConfigException(
            "deployment \"$name\" 的 localPath 必须是相对项目根目录的路径，不能是绝对路径：$localPath",
        )
    }
}

/**
 * 相对路径按项目根解析成绝对路径；已经是绝对路径的原样返回。
 *
 * 只给内部写 IDE Deployment / 读本地文件用，不改变 hosts.json 里存的值。
 */
internal fun resolveLocalPath(basePath: String?, localPath: String): String {
    val path = Path.of(localPath)
    if (path.isAbsolute) return path.normalize().toString()
    if (basePath.isNullOrBlank()) {
        throw DeploymentConfigException("项目没有 basePath，无法解析相对路径：$localPath")
    }
    return Path.of(basePath).resolve(path).normalize().toString()
}
