package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.project.Project
import com.intellij.remote.RemoteCredentialsHolder
import com.jetbrains.plugins.webDeployment.config.AccessType
import com.jetbrains.plugins.webDeployment.config.Deployable
import com.jetbrains.plugins.webDeployment.config.DeploymentPathMapping
import com.jetbrains.plugins.webDeployment.config.GroupedServersConfigManager
import com.jetbrains.plugins.webDeployment.config.PublishConfig
import com.jetbrains.plugins.webDeployment.config.WebServerConfig

/**
 * Deployment 的**平台接线层**：把 `WebServerConfig` / `PublishConfig` 拍平成
 * [DeploymentServerCredentials] —— 只有这一个用途：给 `get_deployment_servers` 提供
 * SFTP server 与凭据。
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
 * 本项目视角下的 **SFTP server + 连接凭据**，给 `get_deployment_servers` 用。
 *
 * 凭据来自平台，不碰 PasswordSafe：`Deployable.copyToCredentials(RemoteCredentialsHolder)`
 * 会把 `Credentials.getPasswordAsString()` 塞进 holder —— PASSWORD 进 `password`，
 * KEY_PAIR 进 `passphrase`（实测，见 `Deployable.copyToCredentials` 的字节码）。
 *
 * 只收 SFTP：FTP / WebDAV / MOUNT 没有 SSH 凭据，`remote_exec` 用不上。
 */
internal fun deploymentServersWithCredentials(project: Project): List<DeploymentServerCredentials> {
    val config = publishConfigOf(project)
    val allMappings = runCatching { config.allPathMappings }.getOrNull().orEmpty()
    val defaultMappings = defaultDeploymentMappings(project)
    val servers = runCatching { GroupedServersConfigManager.getInstance(project).flattenedServers }
        .getOrNull()
        .orEmpty()

    val problems = mutableListOf<String>()
    val result = servers.mapNotNull { server ->
        val accessType = runCatching { server.accessType }.getOrNull()
        if (accessType != AccessType.SFTP) return@mapNotNull null

        val name = server.getName().orEmpty()
        val deployable = runCatching { Deployable.create(server, project) }.getOrNull()
            ?: run {
                problems += "$name: 无法读取服务器配置（Deployable.create 失败）"
                return@mapNotNull null
            }
        val credentials = RemoteCredentialsHolder()
        runCatching { deployable.copyToCredentials(credentials) }
        val host = credentials.host.takeIf { it.isNotBlank() }
        if (host == null) {
            problems += "$name: 没有配置 host"
            return@mapNotNull null
        }

        val id = server.id.orEmpty()
        val mappings = allMappings[id].orEmpty()
            .map { it.toState() }
            .takeUnless { it == defaultMappings }
            .orEmpty()

        val authType = runCatching { credentials.authType.name }.getOrNull() ?: "UNKNOWN"
        val password = credentials.password?.takeIf { it.isNotBlank() }
        val privateKeyPath = credentials.privateKeyFile.takeIf { it.isNotBlank() }?.let(::expandUserHomeMacro)
        val credentialIssue = credentialIssueOf(authType, password, privateKeyPath)
        if (credentialIssue != null) problems += "$name: $credentialIssue"

        DeploymentServerCredentials(
            name = name,
            // 主键落在 SSH 配置上：同一台主机上多套 root/mapping 只是多条 deployment，
            // 不是多台服务器。取不到（SSH 配置被删）时留空，调用方回落到 WebServer 名。
            sshConfigName = runCatching { server.findSshConfig(project)?.name }.getOrNull().orEmpty(),
            host = host,
            port = credentials.port,
            username = credentials.userName.orEmpty(),
            authType = authType,
            password = password,
            passphrase = credentials.passphrase?.takeIf { it.isNotBlank() },
            privateKeyPath = privateKeyPath,
            rootFolder = runCatching { server.fileTransferConfig.rootFolder }.getOrNull(),
            deployments = mappings,
        )
    }

    // Fast fail：带着"有台连不上"的数据继续跑，只会在 remote_exec 时暴露，而且报的是
    // "没有凭据"这种看不出根因的话。配置错误一律在这里挡住，并点名每一台。
    if (problems.isNotEmpty()) {
        throw DeploymentConfigException(
            buildString {
                append("有 ").append(problems.size).append(" 台 SFTP 服务器的配置不完整，无法连接：\n")
                problems.forEach { append("- ").append(it).append('\n') }
                append("请在 IDE 的 Settings | Build, Execution, Deployment | Deployment 里修正后重试。")
            },
        )
    }
    return result
}

/**
 * 认证方式要求的字段是空的 → 这台连不上。
 *
 * 最常见的原因是 IDE 里引用的 SSH 配置被删了（`sshConfigId` 悬空）：`authType` 还是 PASSWORD，
 * 但密码取不到。条目仍然返回（host / mapping 有用），只是把问题带出去让调用方提醒用户。
 */
internal fun credentialIssueOf(authType: String, password: String?, privateKeyPath: String?): String? =
    when (authType) {
        "PASSWORD" -> if (password == null) {
            "authType=PASSWORD 但没有读到密码 —— IDE 里的 SSH 配置可能已被删除或未填写"
        } else {
            null
        }

        "KEY_PAIR" -> if (privateKeyPath == null) "authType=KEY_PAIR 但没有私钥路径" else null
        "OPEN_SSH" -> null
        else -> "无法识别的 authType=$authType"
    }

/**
 * IDE 里的 Deployment 配置不完整（缺 host / 缺凭据 / 引用的 SSH 配置被删）。
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

/** 一条 `DeploymentPathMapping` → 纯数据。 */
private fun DeploymentPathMapping.toState(): DeploymentMappingState = DeploymentMappingState(
    // 这三个 getter 同样没有 @NotNull：字段可能为空，所以用 getOrNull() 兜底再 orEmpty()。
    localPath = runCatching { localPath }.getOrNull().orEmpty(),
    deployPath = runCatching { deployPath }.getOrNull().orEmpty(),
    webPath = runCatching { webPath }.getOrNull().orEmpty(),
)

/**
 * 平台为“没有配置 mapping 的 Server”生成的默认映射。
 *
 * `PublishConfig.getAllPathMappings()` 会把这类默认 mapping 一起返回；用平台自己的
 * `addDefaultMappings()` 生成同一份结果并比较，就能把“用户真的配了 deployment”和
 * “平台补出来的默认值”区分开。这里仍然只走 IDE API，不读项目文件 —— 应用级 Server
 * 也由 `GroupedServersConfigManager` / `PublishConfig` 统一覆盖。
 */
private fun defaultDeploymentMappings(project: Project): List<DeploymentMappingState> {
    val mappings = mutableListOf<DeploymentPathMapping>()
    runCatching { PublishConfig.addDefaultMappings(project, mappings) }
    return mappings.map { it.toState() }
}

