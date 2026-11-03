package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.remote.RemoteCredentials
import com.intellij.ssh.config.unified.SshConfig
import com.intellij.ssh.config.unified.SshConfigManager
import com.jetbrains.plugins.webDeployment.config.AccessType
import com.jetbrains.plugins.webDeployment.config.DeploymentPathMapping
import com.jetbrains.plugins.webDeployment.config.ExcludedPath
import com.jetbrains.plugins.webDeployment.config.GroupedServersConfigManager
import com.jetbrains.plugins.webDeployment.config.PublishConfig
import com.jetbrains.plugins.webDeployment.config.WebServerConfig

/**
 * IDE 侧的两条平台接线：**读 SSH 配置**、**写 Deployment**。
 *
 * 这是整个插件里唯一 import `com.jetbrains.plugins.webDeployment.*` 的文件。
 *
 * ## 为什么要隔离
 *
 * 这个包（`com.jetbrains.*` 而不是 `com.intellij.*`）是**产品内部实现**，不是平台 API：
 * 它没有文档、没有稳定性承诺，而且**只在 Ultimate 里存在**。调用方必须先确认插件在，
 * 再走到这里（见 `deployment.xml` 的链式 optional 依赖）。
 *
 * ## 两条工具各自的边界
 *
 * - `get_ssh_servers`：只读 `SshConfigManager`。deployment / mapping / 排除项全部由
 *   `hosts.json` 维护，这里不返回。
 * - `sync_deployments`：把 `hosts.json` 的 deployment 推到 IDE 的 Deployment 面板
 *   （WebServer + path mapping + excluded paths）。**只增/改，不删。**
 */

internal fun publishConfigOf(project: Project): PublishConfig = PublishConfig.getInstance(project)

/**
 * 本项目视角下的全部 SSH 配置 + 连接凭据，给 `get_ssh_servers` 用。
 *
 * 凭据来自平台，不碰 PasswordSafe 的具体实现：`SshConfig.copyToCredentials(AuthData)`
 * 会把 PasswordSafe 里的密码 / 私钥口令填进 [RemoteCredentials]。
 *
 * 与旧版（遍历 Deployment 面板的 WebServer）的区别：**SSH 配置本身就是服务器**，
 * 没被任何 deployment 引用也照样返回。
 */
internal fun sshServersWithCredentials(project: Project): List<DeploymentServerCredentials> {
    val configs = runCatching { SshConfigManager.getInstance(project).configs }.getOrNull().orEmpty()

    val problems = mutableListOf<String>()
    val result = configs.mapNotNull { config ->
        val name = config.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val credentials = runCatching {
            config.copyToCredentials(config.authDataFromForPasswordSafe)
        }.getOrNull()
        if (credentials == null) {
            problems += "$name: 无法读取凭据"
            return@mapNotNull null
        }

        val host = credentials.host.takeIf { it.isNotBlank() }
        if (host == null) {
            problems += "$name: 没有配置 host"
            return@mapNotNull null
        }

        val authType = runCatching { config.authType.name }.getOrNull() ?: "UNKNOWN"
        val password = credentials.password?.takeIf { it.isNotBlank() }
        val privateKeyPath = credentials.privateKeyFile
            .takeIf { it.isNotBlank() }
            ?.let(::expandUserHomeMacro)
        val credentialIssue = credentialIssueOf(authType, password, privateKeyPath)
        if (credentialIssue != null) problems += "$name: $credentialIssue"

        DeploymentServerCredentials(
            name = name,
            host = host,
            port = credentials.port,
            username = credentials.userName.orEmpty(),
            authType = authType,
            password = password,
            passphrase = credentials.passphrase?.takeIf { it.isNotBlank() },
            privateKeyPath = privateKeyPath,
        )
    }

    // Fast fail：带着"有台连不上"的数据继续跑，只会在 remote_exec 时暴露，而且报的是
    // "没有凭据"这种看不出根因的话。配置错误一律在这里挡住，并点名每一台。
    if (problems.isNotEmpty()) {
        throw DeploymentConfigException(
            buildString {
                append("有 ").append(problems.size).append(" 条 SSH 配置不完整，无法连接：\n")
                problems.forEach { append("- ").append(it).append('\n') }
                append("请在 IDE 的 Settings | Settings | Tools | SSH Configurations 里修正后重试。")
            },
        )
    }
    return result
}

/**
 * 把 `hosts.json` 里的 deployment 推到 IDE。
 *
 * `serverName` 是 SSH 配置名，用来确认目标 WebServer 引用的是同一台机器。
 * 已存在的 WebServer 按名字复用；不存在则新建。
 *
 * 只增/改：IDE 里有、`hosts.json` 没给的 deployment 只会出现在 `orphaned` 里，
 * **不会删除** —— 自动删 IDE 配置风险太大。
 */
internal fun syncDeployments(
    project: Project,
    serverName: String,
    requests: List<DeploymentSyncRequest>,
): DeploymentSyncReport {
    val sshConfig = SshConfigManager.getInstance(project).findConfigByName(serverName)
        ?: throw DeploymentConfigException("IDE 的 SSH 配置里没有 \"$serverName\"，先添加这条 SSH 配置。")

    val manager = GroupedServersConfigManager.getInstance(project)
    val publish = publishConfigOf(project)

    val created = mutableListOf<String>()
    val updated = mutableListOf<String>()
    val unchanged = mutableListOf<String>()
    val handled = mutableSetOf<String>()

    for (request in requests) {
        requireRelativeLocalPath(request.name, request.localPath)
        val existing = manager.flattenedServers.firstOrNull { it.getName() == request.name }
        if (existing == null) {
            val webServer = WebServerConfig().apply {
                // `addServer` 会 clone 之后再加入；id 必须在这里生成，否则写 mapping 时
                // 还没法按名字回查到刚加入的那一条（实测：立刻查 flattenedServers 可能还拿不到）。
                id = java.util.UUID.randomUUID().toString()
                setIsProjectLevel(true)
                fileTransferConfig.apply {
                    accessType = AccessType.SFTP
                    setSshConfig(sshConfig)
                    setRootFolder(request.deployPath)
                }
                // `setSshConfig` 会把 name 重算成 `sftp://host:port`，必须在它之后写回用户给的名字。
                setName(request.name)
            }
            manager.addServer(webServer)
            applyDeployment(project, publish, webServer, request)
            created += request.name
        } else {
            if (existing.accessType != AccessType.SFTP) {
                throw DeploymentConfigException(
                    "deployment \"${request.name}\" 在 IDE 里不是 SFTP 类型，拒绝覆盖。",
                )
            }
            val changed = applyDeployment(project, publish, existing, request)
            if (changed) updated += request.name else unchanged += request.name
        }
        handled += request.name
    }

    val orphaned = manager.flattenedServers
        .filter {
            // 平台会给每条 SSH 配置补一个默认 WebServer（名字就是 `sftp://host:port`），
            // 它不是用户配的 deployment，不能算 orphaned。
            it.accessType == AccessType.SFTP &&
                it.findSshConfig(project)?.name == serverName &&
                !it.getName().orEmpty().startsWith("sftp://")
        }
        .mapNotNull { it.getName() }
        .filterNot { it in handled }

    return DeploymentSyncReport(created, updated, unchanged, orphaned)
}

/**
 * 写入一条 deployment 的 mapping 与 excluded paths。
 *
 * @return 是否真的改动了内容（用于区分 `updated` / `unchanged`）。
 */
private fun applyDeployment(
    project: Project,
    publish: PublishConfig,
    server: WebServerConfig,
    request: DeploymentSyncRequest,
): Boolean {
    val mapping = mappingFor(project, server, request)
    val excludes = request.exclude.map { ExcludedPath(it, true) }

    val currentMappings = publish.getPathMappings(server.id).orEmpty()
    val currentExcludes = publish.getExcludedPaths(server.id).orEmpty()
    val sameMapping = currentMappings.size == 1 &&
        currentMappings.first().localPath == mapping.localPath &&
        currentMappings.first().deployPath == mapping.deployPath
    val sameExcludes = currentExcludes.map { it.path.orEmpty() } == excludes.map { it.path.orEmpty() }
    if (sameMapping && sameExcludes) return false

    // 平台的配置写入口：必须在写锁里，UI 与 MCP 线程都要能看到同一个状态。
    ApplicationManager.getApplication().invokeAndWait {
        publish.setPathMappings(server.id, listOf(mapping))
        publish.setExcludedPaths(server.id, excludes)
    }
    return true
}

/**
 * `hosts.json` 的绝对 `deployPath` → IDE 的「rootFolder + 相对路径」。
 *
 * 能嵌进现有 rootFolder 就嵌进去；嵌不进就把 rootFolder 改成 deployment 目录、
 * mapping 用 `/`。这样拼出来的绝对路径始终等于请求里的 `deployPath`。
 */
private fun mappingFor(
    project: Project,
    server: WebServerConfig,
    request: DeploymentSyncRequest,
): DeploymentPathMapping {
    val root = server.fileTransferConfig.rootFolder?.trim().orEmpty()
    val deployPath = request.deployPath.trim()
    val relative = when {
        root.isNotEmpty() && deployPath == root -> "/"
        root.isNotEmpty() && deployPath.startsWith("$root/") -> deployPath.removePrefix(root)
        else -> {
            server.fileTransferConfig.setRootFolder(deployPath)
            "/"
        }
    }
    return DeploymentPathMapping().apply {
        localPath = resolveLocalPath(project.basePath, request.localPath)
        this.deployPath = relative
        webPath = "/"
    }
}

/**
 * 认证方式要求的字段是空的 → 这条 SSH 配置连不上。
 *
 * 最常见的原因是 IDE 里引用的密码没有保存到 PasswordSafe，或者私钥路径被删。
 */
internal fun credentialIssueOf(authType: String, password: String?, privateKeyPath: String?): String? =
    when (authType) {
        "PASSWORD" -> if (password == null) {
            "authType=PASSWORD 但没有读到密码 —— IDE 里这条 SSH 配置可能没有保存密码"
        } else {
            null
        }

        "KEY_PAIR" -> if (privateKeyPath == null) "authType=KEY_PAIR 但没有私钥路径" else null
        "OPEN_SSH" -> null
        else -> "无法识别的 authType=$authType"
    }

/**
 * IDE 里的 SSH 配置不完整（缺 host / 缺凭据）。
 *
 * 单独一个类型是为了让工具把它转成**可读的配置错误**，而不是 `internal_error`：
 * 这是用户要去 IDE 里修的东西，不是插件崩了。
 */
internal class DeploymentConfigException(message: String) : RuntimeException(message)

/** JetBrains 的路径宏：`$USER_HOME$` / `${USER_HOME}` 换成真实 home。 */
private fun expandUserHomeMacro(path: String): String {
    val home = System.getProperty("user.home").orEmpty()
    return path.replace("\$USER_HOME\$", home).replace("\${USER_HOME}", home)
}
