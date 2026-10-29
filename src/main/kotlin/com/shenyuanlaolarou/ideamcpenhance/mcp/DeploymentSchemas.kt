package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Deployment 工具的**描述符**：名字、说明、输入 schema、输出 schema、行为注解。
 *
 * 与 `RunLifecycleSchemas` 同样的理由集中在单独文件里：描述文案本身就是对外契约，
 * agent 靠它决定"该不该调、参数怎么填、返回怎么读"。
 *
 * 输出 schema 必须和 `DeploymentModels` 里 `toJson()` 逐字段对齐 —— 两边漂移会让严格客户端
 * 在一个完全正常的返回上校验失败。
 */

/** 工具分组。`alwaysIncluded=false`：不勾选也能被主动选用，不污染默认工具列表。 */
internal val DEPLOYMENT_CATEGORY = McpToolCategory(
    "Deployment",
    "com.shenyuanlaolarou.ideamcpenhance.mcp.Deployment",
    false,
    false,
)

// ---------------------------------------------------------------------------
// get_deployment_servers（凭据通道，不进模型可见的工具表）
// ---------------------------------------------------------------------------

internal const val GET_DEPLOYMENT_SERVERS_TOOL_NAME = "get_deployment_servers"

private val CREDENTIAL_MAPPING_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One local ↔ remote directory pair."))
    put(
        "properties",
        properties(
            "name" to property("string", "Deployment name; globally unique in the project."),
            "localPath" to property("string", "Local directory."),
            "deployPath" to property("string", "Remote directory."),
        ),
    )
    put(
        "required",
        buildJsonArray {
            listOf("name", "localPath", "deployPath").forEach { add(JsonPrimitive(it)) }
        },
    )
}

private val DEPLOYMENT_CREDENTIALS_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One SFTP server with the credentials needed to open an SSH session."))
    put(
        "properties",
        properties(
            "name" to property("string", "Deployment name (the WebServer entry in the IDE's Deployment list)."),
            "sshConfigName" to property(
                "string",
                "SSH configuration name this deployment uses; the OpenCode plugin keys servers by this.",
            ),
            "host" to property("string", "Host name or IP."),
            "port" to property("integer", "SSH port."),
            "username" to property("string", "SSH user name."),
            "authType" to property(
                "string",
                "PASSWORD, KEY_PAIR or OPEN_SSH. PASSWORD uses `password`, KEY_PAIR uses " +
                    "`privateKeyPath` (+ `passphrase`), OPEN_SSH relies on ~/.ssh/config.",
            ),
            "password" to nullableProperty("string", "Password; present only for authType=PASSWORD."),
            "passphrase" to nullableProperty("string", "Private-key passphrase; present only when the key needs one."),
            "privateKeyPath" to nullableProperty(
                "string",
                "Absolute private-key path; present only for authType=KEY_PAIR.",
            ),
            "rootFolder" to nullableProperty("string", "Server root folder as configured in the IDE."),
            "deployments" to arrayOf(
                CREDENTIAL_MAPPING_SCHEMA,
                "Path mappings attached to this server in this project (transfer needs both ends).",
            ),
        ),
    )
    put(
        "required",
        buildJsonArray {
            listOf(
                "name", "sshConfigName", "host", "port", "username", "authType", "password",
                "passphrase", "privateKeyPath", "rootFolder", "deployments",
            ).forEach { add(JsonPrimitive(it)) }
        },
    )
}

internal val getDeploymentServersDescriptor = McpToolDescriptor(
    GET_DEPLOYMENT_SERVERS_TOOL_NAME,
    "Get Deployment Server Credentials",
    """
    Return this project's SFTP servers together with the credentials needed to open an SSH session.

    This is a data channel for the OpenCode plugin, not a task tool: the plugin calls it once per
    cache window and uses the result to run commands over SSH (`remote_exec`), so it never has to keep
    its own copy of host / user / password in a config file.

    Only SFTP servers are returned: FTP / WebDAV / MOUNT have no SSH credentials.

    A server missing `host` is skipped. `password`, `passphrase` and `privateKeyPath` follow `authType`
    — PASSWORD fills `password`, KEY_PAIR fills `privateKeyPath` (plus `passphrase` when the key is
    encrypted), OPEN_SSH relies on the user's own OpenSSH config and may carry neither.

    Never print the credential fields anywhere they could reach a model or a log.
    """.trimIndent(),
    DEPLOYMENT_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.get_deployment_servers",
    schema(properties = properties("projectPath" to projectPathProperty())),
    schema(
        properties = properties(
            "servers" to arrayOf(DEPLOYMENT_CREDENTIALS_SCHEMA, "Every SFTP server usable from this project."),
        ),
        required = setOf("servers"),
    ),
    annotations(readOnly = true, destructive = false, idempotent = true, openWorld = false),
)
