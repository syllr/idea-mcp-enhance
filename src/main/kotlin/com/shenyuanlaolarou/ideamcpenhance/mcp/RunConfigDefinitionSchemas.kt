package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 定义态工具的描述符。
 *
 * 描述文案是**对外契约**（agent 靠它决定怎么调），与 [RunLifecycleSchemas] 里的描述符同规格：
 * 根级 `type: object` + `required` 数组 + 逐字照抄内置的 `projectPath` 描述。
 *
 * schema 拼装用的公共片段（`schema` / `properties` / `property` / …）来自 [RunLifecycleSchemas]，
 * 两处共用一份，避免定义漂移。
 */

/** 一次历史运行的形状。`inspect` 的 `history[]` 与 `beforeRunTasks[].recentRuns[]` 共用。 */
internal val HISTORY_ENTRY_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("properties", properties(
        "startedAt" to property("integer", "When the run started, in epoch milliseconds."),
        "startedAtIso" to property("string", "When the run started, in ISO-8601."),
        "endedAt" to nullableProperty("integer", "When the process exited, in epoch milliseconds."),
        "durationMs" to nullableProperty("integer", "How long it ran, in milliseconds."),
        "pid" to nullableProperty("integer", "Operating system process id."),
        "exitCode" to nullableProperty("integer", "Process exit code."),
        "logFile" to property("string", "The log file that run produced."),
    ))
    put("required", buildJsonArray {
        add(JsonPrimitive("startedAt"))
        add(JsonPrimitive("startedAtIso"))
        add(JsonPrimitive("endedAt"))
        add(JsonPrimitive("durationMs"))
        add(JsonPrimitive("pid"))
        add(JsonPrimitive("exitCode"))
        add(JsonPrimitive("logFile"))
    })
}

/** 定义态工具的分类。 */
internal val RUN_CONFIG_DEFINITION_CATEGORY = McpToolCategory(
    "Run Configuration",
    "com.shenyuanlaolarou.ideamcpenhance.mcp.RunConfiguration",
    false,
    false,
)

internal const val INSPECT_TOOL_NAME = "inspect_run_configuration"

internal val inspectRunConfigurationDescriptor = McpToolDescriptor(
    INSPECT_TOOL_NAME,
    "Inspect Run Configuration",
    """
    Show one run configuration in detail: settings, runnability, live instances and earlier run logs.

    Read-only — run configurations are created and edited in the IDE, not through this tool. Use
    `list_running_configurations` for the live index and this tool for options, environment,
    before-launch steps, raw XML and history.

    The name may carry a variant: `[os@]base[@env]` (`win@console-web` is the Windows variant,
    `console-web@testenv` is one environment); no prefix means macOS/Linux, no environment suffix
    means the configuration applies to every environment.

    `beforeRunTasks[]` describes the before-launch steps (`taskKind`, `name`, `typeId`, `enabled`) and,
    for each, `recentRuns[]` — that launched configuration's own recent runs, newest first, with
    `exitCode` and `logFile`. A before-launch build runs as its own Run session, so its log never
    appears in this configuration's `history[]`; this is the only place that chain is connected.
    `recentRuns[]` is capped at 3 and is not affected by `historyLimit`.

    `instances[]` are live, with pid, state and log path. `history[]` is newest-first; read its log
    file for the output of an earlier run. History only covers runs captured by this plugin; a shell
    configuration executed in the IDE terminal has no captured log.
    """.trimIndent(),
    RUN_CONFIG_DEFINITION_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.inspect_run_configuration",
    schema(
        properties = properties(
            "configurationName" to property(
                "string",
                "Name of the run configuration to inspect.",
            ),
            "historyLimit" to property(
                "integer",
                "How many earlier runs to include in `history[]`, newest first. Defaults to 20. Values <= 0 " +
                    "fall back to the default.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("configurationName"),
    ),
    schema(
        properties = properties(
            "configurationName" to property("string", "Name of the run configuration."),
            "type" to property("string", "Run configuration type id, e.g. ShConfigurationType."),
            "typeDisplayName" to property(
                "string",
                "Human readable type name, e.g. Shell Script. `Unknown` when the plugin that provides the type " +
                    "is not installed.",
            ),
            "typeKnown" to property(
                "boolean",
                "false when this IDE cannot resolve the type (the plugin is missing). The file's `type` attribute " +
                    "is still reported in `type`, and `configurationXml` is intact — the configuration would work " +
                    "on a machine that has the plugin.",
            ),
            "storage" to buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("description", JsonPrimitive("Where the configuration is stored."))
                put("properties", properties(
                    "kind" to property(
                        "string",
                        "`runFile` (project-level, shared with the team), `dotIdea`, `workspace`, " +
                            "`temporary` or `unknown`.",
                    ),
                    "path" to nullableProperty("string", "Path of the file holding it; null when it has none."),
                    "shared" to property("boolean", "true when the configuration is shared with the team."),
                ))
                put("required", buildJsonArray {
                    add(JsonPrimitive("kind"))
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("shared"))
                })
            },
            "isTemplate" to property("boolean", "true for run configuration templates."),
            "canRun" to property("boolean", "Whether the IDE's own check accepts running it right now."),
            "canRunBlockedReason" to nullableProperty(
                "string",
                "Why it cannot run yet; null when `canRun` is true.",
            ),
            "naming" to buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("description", JsonPrimitive("The name parsed into platform / base / environment."))
                put("properties", properties(
                    "platform" to nullableProperty("string", "`win` or `mac-linux`."),
                    "base" to nullableProperty("string", "The configuration itself, without platform or environment."),
                    "env" to nullableProperty("string", "Environment name; null when it applies to every environment."),
                ))
                put("required", buildJsonArray {
                    add(JsonPrimitive("platform"))
                    add(JsonPrimitive("base"))
                    add(JsonPrimitive("env"))
                })
            },
            "attributes" to property("object", "Attributes of the configuration tag; some types keep fields there."),
            "options" to property(
                "object",
                "The `<option name= value=>` entries of this configuration. Types that keep their fields in " +
                    "child elements instead (npm: `<package-json>`, Gradle: `<ExternalSystemSettings>`) show them " +
                    "in `configurationXml`, not here.",
            ),
            "envs" to property("object", "The environment variables this configuration sets."),
            "beforeRunTasks" to arrayOf(
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("description", JsonPrimitive(
                        "A before-launch step: another run configuration this one launches first, " +
                            "with that configuration's own recent runs.",
                    ))
                    put("properties", properties(
                        "taskKind" to nullableProperty(
                            "string",
                            "Kind of the before-launch task, e.g. `RunConfigurationTask` (runs another " +
                                "configuration) or `MakeBeforeRunTask`.",
                        ),
                        "name" to nullableProperty(
                            "string",
                            "The run configuration this step launches; null for steps that do not run one.",
                        ),
                        "typeId" to nullableProperty(
                            "string",
                            "Type id of the launched configuration, e.g. `NodeJSConfigurationType`.",
                        ),
                        "enabled" to property(
                            "boolean",
                            "false when the step is switched off in the IDE; a disabled step does not run.",
                        ),
                        "recentRuns" to arrayOf(
                            HISTORY_ENTRY_SCHEMA,
                            "Runs of the launched configuration, newest first — **at most 3, may be empty**. " +
                                "The most recent run is `recentRuns[0]`; read its `logFile` for the output.",
                        ),
                    ))
                    put("required", buildJsonArray {
                        add(JsonPrimitive("taskKind"))
                        add(JsonPrimitive("name"))
                        add(JsonPrimitive("typeId"))
                        add(JsonPrimitive("enabled"))
                        add(JsonPrimitive("recentRuns"))
                    })
                },
                "Before-launch steps of this configuration, in execution order. Empty when it has none.",
            ),
            "configurationXml" to nullableProperty(
                "string",
                "The `<configuration>` XML of this configuration, as stored.",
            ),
            "instances" to arrayOf(
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", properties(
                        "executionId" to property("integer", "IDE-assigned execution id of this Run content."),
                        "state" to property("string", "`running`, `terminating` or `starting`."),
                        "pid" to nullableProperty("integer", "Operating system process id."),
                        "startedAt" to nullableProperty("integer", "When the run started, in epoch milliseconds."),
                        "startedAtIso" to nullableProperty("string", "When the run started, in ISO-8601."),
                        "uptimeMs" to nullableProperty("integer", "How long it has been running, in milliseconds."),
                        "logFile" to nullableProperty("string", "The log file this instance writes to."),
                    ))
                    put("required", buildJsonArray {
                        add(JsonPrimitive("executionId"))
                        add(JsonPrimitive("state"))
                        add(JsonPrimitive("pid"))
                        add(JsonPrimitive("startedAt"))
                        add(JsonPrimitive("startedAtIso"))
                        add(JsonPrimitive("uptimeMs"))
                        add(JsonPrimitive("logFile"))
                    })
                },
                "The live instances of this configuration.",
            ),
            "history" to arrayOf(
                HISTORY_ENTRY_SCHEMA,
                "Earlier runs of this configuration, newest first.",
            ),
            "warnings" to WARNINGS_SCHEMA,
        ),
        required = setOf(
            "configurationName",
            "type",
            "typeDisplayName",
            "typeKnown",
            "storage",
            "isTemplate",
            "canRun",
            "canRunBlockedReason",
            "naming",
            "attributes",
            "options",
            "envs",
            "beforeRunTasks",
            "configurationXml",
            "instances",
            "history",
            "warnings",
        ),
    ),
    annotations(readOnly = true, destructive = false, idempotent = true),
)


/** 定义态工具的公共片段。 */
internal val DEFINITION_WARNINGS_SCHEMA: JsonObject = WARNINGS_SCHEMA
