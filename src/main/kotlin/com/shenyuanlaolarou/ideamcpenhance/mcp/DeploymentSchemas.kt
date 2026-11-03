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
 * 输出 schema 必须和 `DeploymentModels` 里 `toJson()` 逐字段对齐 —— 两边漂移会让严格客户端
 * 在一个完全正常的返回上校验失败。
 *
 * 两个工具都不进模型可见的工具表（都是 OpenCode 的数据通道）：
 *
 * - `get_ssh_servers`：SSH 配置 + 凭据
 * - `sync_deployments`：把 `hosts.json` 的 deployment 推到 IDE
 */

/** 工具分组。`alwaysIncluded=false`：不勾选也能被主动选用，不污染默认工具列表。 */
internal val DEPLOYMENT_CATEGORY = McpToolCategory(
    "Deployment",
    "com.shenyuanlaolarou.ideamcpenhance.mcp.Deployment",
    false,
    false,
)

// ---------------------------------------------------------------------------
// get_ssh_servers（凭据通道，不进模型可见的工具表）
// ---------------------------------------------------------------------------

internal const val GET_SSH_SERVERS_TOOL_NAME = "get_ssh_servers"

private val SSH_CREDENTIALS_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One SSH configuration with the credentials needed to open a session."))
    put(
        "properties",
        properties(
            "name" to property("string", "SSH configuration name; the OpenCode plugin keys servers by this."),
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
        ),
    )
    put(
        "required",
        buildJsonArray {
            listOf(
                "name", "host", "port", "username", "authType",
                "password", "passphrase", "privateKeyPath",
            ).forEach { add(JsonPrimitive(it)) }
        },
    )
}

internal val getSshServersDescriptor = McpToolDescriptor(
    GET_SSH_SERVERS_TOOL_NAME,
    "Get SSH Server Credentials",
    """
    Return this project's SSH configurations together with the credentials needed to open a session.

    This is a data channel for the OpenCode plugin, not a task tool: the plugin calls it once per
    cache window and uses the result to run commands over SSH (`remote_exec`), so it never has to keep
    its own copy of host / user / password in a config file.

    Only SSH configurations are returned. Deployments, path mappings and excluded paths live in the
    plugin's own `hosts.json`; this tool does not know about them.

    A configuration missing `host` is skipped. `password`, `passphrase` and `privateKeyPath` follow
    `authType` — PASSWORD fills `password`, KEY_PAIR fills `privateKeyPath` (plus `passphrase` when
    the key is encrypted), OPEN_SSH relies on the user's own OpenSSH config and may carry neither.

    Never print the credential fields anywhere they could reach a model or a log.
    """.trimIndent(),
    DEPLOYMENT_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.get_ssh_servers",
    schema(properties = properties("projectPath" to projectPathProperty())),
    schema(
        properties = properties(
            "servers" to arrayOf(SSH_CREDENTIALS_SCHEMA, "Every SSH configuration usable from this project."),
        ),
        required = setOf("servers"),
    ),
    annotations(readOnly = true, destructive = false, idempotent = true, openWorld = false),
)

// ---------------------------------------------------------------------------
// sync_deployments（hosts.json → IDE，不进模型可见的工具表）
// ---------------------------------------------------------------------------

internal const val SYNC_DEPLOYMENTS_TOOL_NAME = "sync_deployments"

private val DEPLOYMENT_SYNC_ITEM_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One deployment from hosts.json to create or update in the IDE."))
    put(
        "properties",
        properties(
            "name" to property("string", "Deployment name; the WebServer entry in the IDE's Deployment list."),
            "localPath" to property("string", "Local directory relative to the project root."),
            "deployPath" to property("string", "Absolute remote directory."),
            "exclude" to arrayOf(
                property("string", "Path prefix or exact file; no `*` or `?` wildcards."),
                "Paths that must not be transferred.",
            ),
        ),
    )
    put(
        "required",
        buildJsonArray {
            listOf("name", "localPath", "deployPath", "exclude").forEach { add(JsonPrimitive(it)) }
        },
    )
}

internal val syncDeploymentsDescriptor = McpToolDescriptor(
    SYNC_DEPLOYMENTS_TOOL_NAME,
    "Sync Deployments To IDE",
    """
    Create or update the IDE's Deployment entries from the plugin's `hosts.json`.

    For every item this writes: the WebServer (named after the deployment), its single path mapping
    (`localPath` → `deployPath`) and its excluded paths. The WebServer must use the SSH
    configuration named by `server`.

    Only create/update. A deployment that exists in the IDE but is absent from the request is
    reported as `orphaned` and is never deleted.

    `exclude` accepts path prefixes and exact file paths only; patterns with `*` or `?` are rejected.
    """.trimIndent(),
    DEPLOYMENT_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.sync_deployments",
    schema(
        properties = properties(
            "projectPath" to projectPathProperty(),
            "server" to property("string", "SSH configuration name the deployments must use."),
            "deployments" to arrayOf(DEPLOYMENT_SYNC_ITEM_SCHEMA, "Deployments to create or update."),
        ),
        required = setOf("server", "deployments"),
    ),
    schema(
        properties = properties(
            "created" to arrayOf(property("string", "Deployment names created in the IDE.")),
            "updated" to arrayOf(property("string", "Deployment names whose mapping or excludes changed.")),
            "unchanged" to arrayOf(property("string", "Deployment names already up to date.")),
            "orphaned" to arrayOf(
                property("string", "Deployments present in the IDE but absent from the request; not deleted."),
            ),
        ),
        required = setOf("created", "updated", "unchanged", "orphaned"),
    ),
    annotations(readOnly = false, destructive = false, idempotent = true, openWorld = false),
)
