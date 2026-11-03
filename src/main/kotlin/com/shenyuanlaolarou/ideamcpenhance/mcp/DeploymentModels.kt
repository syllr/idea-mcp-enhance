package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Deployment 工具的**纯数据层**。
 *
 * 与 `RunLifecycleModels` 同一条分工：本文件不含任何平台类型、不做 IO，只负责"数据 → JSON"。
 * 平台接线在 `DeploymentAccess`，那里是**唯一** import `com.jetbrains.plugins.webDeployment.*` 的地方。
 *
 * 两个工具各有一条边界：
 *
 * - `get_ssh_servers`：只读 IDE 的 SSH 配置（host / 凭据）。deployment、mapping、排除项
 *   全部由 `hosts.json` 维护，这里不返回。
 * - `sync_deployments`：把 `hosts.json` 里的 deployment 推到 IDE 的 Deployment 面板。
 */

/**
 * 一条 SSH 配置的连接凭据，给 `get_ssh_servers` 用。
 *
 * @param name SSH 配置名（`SshConfig.name`），也是 OpenCode 侧的服务器主键。
 * @param authType PASSWORD / KEY_PAIR / OPEN_SSH。调用方据此决定用 `password` 还是
 *   `privateKeyPath`；OPEN_SSH 两者都可能为空（凭据由 `~/.ssh/config` 提供）。
 * @param password 仅 PASSWORD 有值。**不要打印、不要写日志**。
 * @param passphrase 仅 KEY_PAIR 且私钥带口令时有值。
 * @param privateKeyPath 仅 KEY_PAIR 有值；已展开 `$USER_HOME$`。
 */
data class DeploymentServerCredentials(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,
    val password: String?,
    val passphrase: String?,
    val privateKeyPath: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authType", authType)
        put("password", password)
        put("passphrase", passphrase)
        put("privateKeyPath", privateKeyPath)
    }
}

/** `get_ssh_servers` 的完整返回。 */
data class DeploymentCredentialsReport(val servers: List<DeploymentServerCredentials>) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("servers") { for (server in servers) add(server.toJson()) }
    }
}

/**
 * `sync_deployments` 的一条输入：`hosts.json` 里的 deployment。
 *
 * @param deployPath **绝对**远端目录。IDE 的 mapping 存的是相对 rootFolder 的路径，
 *   转换在 `DeploymentAccess` 里做。
 * @param exclude 路径前缀或精确文件；不支持 `*` / `?`。
 */
data class DeploymentSyncRequest(
    val name: String,
    val localPath: String,
    val deployPath: String,
    val exclude: List<String> = emptyList(),
)

/**
 * `sync_deployments` 的结果。
 *
 * @param orphaned IDE 里存在、但 `hosts.json` 没给的 deployment。**只报告，不删除。**
 */
data class DeploymentSyncReport(
    val created: List<String> = emptyList(),
    val updated: List<String> = emptyList(),
    val unchanged: List<String> = emptyList(),
    val orphaned: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("created") { created.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        putJsonArray("updated") { updated.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        putJsonArray("unchanged") { unchanged.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        putJsonArray("orphaned") { orphaned.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }
}
