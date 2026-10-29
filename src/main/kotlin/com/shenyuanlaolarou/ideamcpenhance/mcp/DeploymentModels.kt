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
    /** 对外只暴露这个稳定名称；由 localPath 的最后一段派生，例如 `.../mcp-test-project` → `mcp-test-project`。 */
    val name: String
        get() = localPath.trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { deployPath.trimEnd('/', '\\').substringAfterLast('/').ifBlank { "deployment" } }

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
    }
}

/**
 * 一个 SFTP server 的**连接凭据**，给 `get_deployment_servers` 用。
 *
 * 这个形状故意不暴露给模型（工具在 `opencode-idea` 的隐藏清单里）：它是那个插件
 * `remote_exec` 的数据通道，不是任务工具。
 *
 * 只收 SFTP：FTP / WebDAV / MOUNT 没有 SSH 凭据，`remote_exec` 也用不上。
 *
 * @param authType PASSWORD / KEY_PAIR / OPEN_SSH。调用方据此决定用 `password` 还是
 *   `privateKeyPath`；OPEN_SSH 两者都可能为空（凭据由 `~/.ssh/config` 提供）。
 * @param password 仅 PASSWORD 有值。**不要打印、不要写日志**。
 * @param passphrase 仅 KEY_PAIR 且私钥带口令时有值。
 * @param privateKeyPath 仅 KEY_PAIR 有值；已展开 `$USER_HOME$`。
 */
data class DeploymentServerCredentials(
    val name: String,
    val sshConfigName: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,
    val password: String?,
    val passphrase: String?,
    val privateKeyPath: String?,
    val rootFolder: String?,
    val deployments: List<DeploymentMappingState>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        // `name` 是 Deployment 面板里的 WebServer 名；`sshConfigName` 是它引用的 SSH 配置名。
        // 调用方用后者当服务器主键（一台 SSH 主机），前者只作诊断。
        put("sshConfigName", sshConfigName)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authType", authType)
        put("password", password)
        put("passphrase", passphrase)
        put("privateKeyPath", privateKeyPath)
        put("rootFolder", rootFolder)
        // 这里比 `list_server_deployments` 多给 localPath / deployPath：上传 / 下载要靠它
        // 定位两边的目录，只给 name 的话调用方没法自己拼。
        //
        // `name` 用 **WebServer 名**（Deployment 面板左边那一条），不是 localPath 末段：
        // 用户配一条 deployment 就是在面板里建一条 WebServer，那才是他调用时看到的名字。
        // 一个 WebServer 挂多条 mapping 时才追加序号（正常是一对一）。
        putJsonArray("deployments") {
            for ((index, deployment) in deployments.withIndex()) {
                add(buildJsonObject {
                    put("name", if (deployments.size == 1) name else "$name-${index + 1}")
                    put("localPath", deployment.localPath)
                    // IDE 的 mapping 里 `deployPath` 是**相对 rootFolder** 的（`/` 表示服务器根），
                    // 直接拿它当目标目录会把文件传到 `/` —— 这里拼成调用方能直接用的完整路径。
                    put("deployPath", resolveRemotePath(rootFolder, deployment.deployPath))
                })
            }
        }
    }
}

/** `get_deployment_servers` 的完整返回。 */
data class DeploymentCredentialsReport(val servers: List<DeploymentServerCredentials>) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("servers") { for (server in servers) add(server.toJson()) }
    }
}

/**
 * IDE 的 mapping 路径是相对 server 的 `rootFolder` 的：`deploy="/"` 表示 rootFolder 本身。
 *
 * 拼成绝对路径再交给调用方，省掉"先读 rootFolder 再自己 join"这一步 —— 远端是 POSIX 路径，
 * 这里统一用 `/`。
 */
private fun resolveRemotePath(rootFolder: String?, deployPath: String): String {
    val root = rootFolder?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return deployPath
    val child = deployPath.trim().trimStart('/')
    return if (child.isEmpty()) root else "$root/$child"
}
