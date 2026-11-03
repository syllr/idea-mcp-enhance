package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `get_project_env` 的对外契约。
 *
 * 这个工具**不是给模型用的**：它是 `opencode-idea` 在 `shell.hook("create.before")` 里
 * 直接 HTTP 调用的数据通道，所以在那个插件的隐藏清单里。
 *
 * 输出：`projectPath`（这个环境属于哪个 IDE 项目）+ `PATH`（五个 provider 的 bin，按固定顺序拼好）
 * + `variables`（各 provider 的标量变量）。`projectPath` 让调用方校验"IDE 打开的是不是同一个目录"。
 */

internal val ENVIRONMENT_CATEGORY = McpToolCategory(
    "Environment",
    "com.shenyuanlaolarou.ideamcpenhance.mcp.Environment",
    false,
    false,
)

internal const val GET_PROJECT_ENV_TOOL_NAME = "get_project_env"

private val PROJECT_ENV_OUTPUT_PROPERTIES: JsonObject = properties(
    "projectPath" to property(
        "string",
        "Absolute path of the IDE project this environment belongs to. Empty when the project has no base path.",
    ),
    "PATH" to property(
        "string",
        "Directories contributed by the five providers, joined with the platform path separator " +
            "in a fixed order: Java, Node, Go, Maven, Python. Contains no system PATH entries.",
    ),
    "variables" to buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "description",
            JsonPrimitive("Scalar variables contributed by the providers, e.g. GOROOT, GOPATH, VIRTUAL_ENV."),
        )
        put(
            "additionalProperties",
            buildJsonObject { put("type", JsonPrimitive("string")) },
        )
    },
)

internal val getProjectEnvDescriptor = McpToolDescriptor(
    GET_PROJECT_ENV_TOOL_NAME,
    "Get Project Environment",
    """
    Return the environment a project's configured SDKs and runtimes contribute.

    This is a data channel for the OpenCode plugin, not a task tool: the plugin calls it when an
    IDE session connects and injects the result into every native shell it creates. It never runs
    a command and never touches the IDE's own terminal.

    `PATH` holds only what the providers contribute (project JDK, Node runtime and package
    manager, Go SDK, Maven home, Python interpreter), in a fixed order. It deliberately excludes
    the IDE process's own PATH so the caller can prepend it to its existing PATH instead of
    replacing it.

    A missing provider simply contributes nothing; the call still succeeds with what is left.
    """.trimIndent(),
    ENVIRONMENT_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.get_project_env",
    schema(properties = properties("projectPath" to projectPathProperty())),
    schema(properties = PROJECT_ENV_OUTPUT_PROPERTIES, required = setOf("projectPath", "PATH", "variables")),
    annotations(readOnly = true, destructive = false, idempotent = true, openWorld = false),
)
