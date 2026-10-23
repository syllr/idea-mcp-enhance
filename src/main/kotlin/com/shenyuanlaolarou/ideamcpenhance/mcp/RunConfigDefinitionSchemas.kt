package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 定义态工具（`inspect` / `create` / `update` / `delete`）的描述符。
 *
 * 描述文案是**对外契约**（agent 靠它决定怎么调），与 [RunLifecycleSchemas] 里的描述符同规格：
 * 根级 `type: object` + `required` 数组 + 逐字照抄内置的 `projectPath` 描述。
 *
 * schema 拼装用的公共片段（`schema` / `properties` / `property` / …）来自 [RunLifecycleSchemas]，
 * 两处共用一份，避免定义漂移。
 */

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

    Use `list_running_configurations` for the live index; use this tool for options, environment,
    before-launch steps, raw XML and history.

    Names are parsed as `[os@]base[@env]`: `win@console-web` is the Windows variant,
    `console-web@testenv` is one environment. No prefix means macOS/Linux; no environment suffix
    means the configuration applies to every environment.

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
            "beforeLaunchSteps" to arrayOf(
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", properties(
                        "type" to nullableProperty("string", "Kind of the before-launch step."),
                        "referencedConfiguration" to nullableProperty(
                            "string",
                            "The run configuration this step runs, when it runs another configuration.",
                        ),
                    ))
                    put("required", buildJsonArray {
                        add(JsonPrimitive("type"))
                        add(JsonPrimitive("referencedConfiguration"))
                    })
                },
                "Before-launch steps of this configuration.",
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
                buildJsonObject {
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
                },
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
            "beforeLaunchSteps",
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

// ---------------------------------------------------------------------------
// create_run_configuration
// ---------------------------------------------------------------------------

internal const val CREATE_DEFINITION_TOOL_NAME = "create_run_configuration"

internal val createRunConfigurationDefinitionDescriptor = McpToolDescriptor(
    CREATE_DEFINITION_TOOL_NAME,
    "Create Run Configuration",
    """
    Create a project-level run configuration — a durable team asset.

    Do not create or edit that file yourself: this tool (with `update_run_configuration` and
    `delete_run_configuration`) is the only writer. A hand-written file fails silently when its type
    or field names are wrong.

    Ask the user before creating anything. State the name, type, command and why it is worth keeping,
    then wait for agreement. For a one-off command, run it another way.

    Body source, in order of preference:
    1. `cloneFrom`: copy an existing configuration of the same type. Safest for variants and when the
       type's field names are unknown.
    2. `configurationXml`: the `<configuration>` XML (advanced; needed for nested settings such as
       Gradle).
    3. Neither: use the IDE's template for `type`, then patch it.

    `options` / `attributes` / `envs` are applied on top. Shell configurations are reliably
    field-complete this way; for other types, prefer `cloneFrom`.
    """.trimIndent(),
    RUN_CONFIG_DEFINITION_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.create_run_configuration",
    schema(
        properties = properties(
            "configurationName" to property("string", "Name of the new run configuration. Must be free."),
            "type" to property(
                "string",
                "Configuration type id, e.g. `ShConfigurationType`, `PythonConfigurationType`, " +
                    "`js.build_tools.npm`. Required unless `cloneFrom` is given (then it is inferred and must match).",
            ),
            "cloneFrom" to property(
                "string",
                "Name of an existing configuration to copy. Any type is allowed; the source must not be a template.",
            ),
            "configurationXml" to property(
                "string",
                "The `<configuration>` XML (or just its inner content) to use as the body. Advanced.",
            ),
            "options" to property(
                "object",
                "`<option name= value=>` entries to set, as a JSON object of string to string.",
            ),
            "attributes" to property(
                "object",
                "Attributes of the `<configuration>` tag, as a JSON object of string to string. " +
                    "Some types keep fields there (Node.js: `path-to-js-file`, `working-dir`).",
            ),
            "envs" to property(
                "object",
                "Environment variables, as a JSON object of string to string.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("configurationName"),
    ),
    schema(
        properties = properties(
            "configurationName" to property("string", "Name of the created run configuration."),
            "type" to property("string", "Configuration type id."),
            "typeDisplayName" to property("string", "Human readable type name."),
            "createdFrom" to property(
                "string",
                "How the body was produced: `clone:<name>`, `template:<type>` or `xml`.",
            ),
            "storagePath" to property("string", "Absolute path of the file holding the configuration."),
            "canRun" to property("boolean", "Whether the IDE's own check accepts running it right now."),
            "canRunBlockedReason" to nullableProperty("string", "Why it cannot run yet; null when it can."),
            "unknownOptions" to arrayOf(
                property("string", "An option name the platform did not keep."),
                "Options that were requested but are not recognised by this configuration type — proof of a " +
                    "misspelled field name. Empty when everything was kept.",
            ),
            "warnings" to WARNINGS_SCHEMA,
        ),
        required = setOf(
            "configurationName",
            "type",
            "typeDisplayName",
            "typeKnown",
            "createdFrom",
            "storagePath",
            "canRun",
            "canRunBlockedReason",
            "unknownOptions",
            "warnings",
        ),
    ),
    annotations(readOnly = false, destructive = false, idempotent = false),
)

// ---------------------------------------------------------------------------
// update_run_configuration
// ---------------------------------------------------------------------------

internal const val UPDATE_TOOL_NAME = "update_run_configuration"

internal val updateRunConfigurationDescriptor = McpToolDescriptor(
    UPDATE_TOOL_NAME,
    "Update Run Configuration",
    """
    Change an existing project-level run configuration, including its name.

    Do not edit the configuration's file yourself: this tool is the writer and re-checks that the
    result still runs.

    Fields (omit `newName`): `options` / `attributes` / `envs` are patches; `null` removes a key.
    `replaceConfigurationXml` replaces the body for nested types such as Gradle/npm. Changes apply on
    the next start; if it is running, call `rerun_run_configuration` afterwards.

    Rename (`newName`): moves the file and rewrites references from before-launch steps and compound
    configurations. Recorded run history moves too. Refused while running or when the name is taken.

    `expectedContentHash` prevents overwriting a file that changed since you read it. Only project-level
    configurations can be changed here; edit IDE-level ones in the IDE.
    """.trimIndent(),
    RUN_CONFIG_DEFINITION_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.update_run_configuration",
    schema(
        properties = properties(
            "configurationName" to property("string", "Current name of the configuration to change."),
            "options" to property(
                "object",
                "`<option>` entries to set; a `null` value removes that option.",
            ),
            "removeOptions" to arrayOf(
                property("string", "Option name."),
                "Option names to remove. Same effect as setting them to `null`.",
            ),
            "attributes" to property(
                "object",
                "Attributes of the `<configuration>` tag to set; a `null` value removes that attribute.",
            ),
            "envs" to property(
                "object",
                "Environment variables to set; a `null` value removes that variable.",
            ),
            "replaceConfigurationXml" to property(
                "string",
                "Replace the whole `<configuration>` body with this XML. Advanced; conflicts with the patch " +
                    "arguments. This is the way to change a type whose fields are child elements rather than " +
                    "`<option>` entries (npm, Gradle) — read `configurationXml` with `inspect_run_configuration` " +
                    "first, edit it, and pass it back here.",
            ),
            "newName" to property(
                "string",
                "Rename the configuration to this name (the file is moved and references are rewritten).",
            ),
            "expectedContentHash" to property(
                "string",
                "SHA-256 (hex) of the current `<configuration>` XML. When given, the update is refused if the " +
                    "file no longer matches — protects against overwriting someone else's change.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("configurationName"),
    ),
    schema(
        properties = properties(
            "configurationName" to property("string", "Name after the update (the new name when renamed)."),
            "previousName" to nullableProperty("string", "The old name; null when it was not renamed."),
            "renamed" to property("boolean", "true when the name changed."),
            "storagePath" to property("string", "Absolute path of the file holding the configuration."),
            "changed" to arrayOf(
                property("string", "What was changed, e.g. `option:SCRIPT_TEXT`, `env:APP_ENV`, `name`."),
                "The changes that were applied, in the order they were applied.",
            ),
            "referencesUpdated" to arrayOf(
                property("string", "Name of the configuration whose reference was rewritten."),
                "Other configurations whose reference to this one was updated (rename only).",
            ),
            "logFilesMoved" to property("integer", "How many recorded run logs were moved to the new name (rename only)."),
            "canRun" to property("boolean", "Whether the IDE's own check accepts running it right now."),
            "canRunBlockedReason" to nullableProperty("string", "Why it cannot run yet; null when it can."),
            "unknownOptions" to arrayOf(
                property("string", "An option name the platform did not keep."),
                "Requested options this type does not recognise — proof of a misspelled field name.",
            ),
            "warnings" to WARNINGS_SCHEMA,
        ),
        required = setOf(
            "configurationName",
            "previousName",
            "renamed",
            "storagePath",
            "changed",
            "referencesUpdated",
            "logFilesMoved",
            "canRun",
            "canRunBlockedReason",
            "unknownOptions",
            "warnings",
        ),
    ),
    annotations(readOnly = false, destructive = false, idempotent = true),
)

// ---------------------------------------------------------------------------
// delete_run_configuration
// ---------------------------------------------------------------------------

internal const val DELETE_TOOL_NAME = "delete_run_configuration"

internal val deleteRunConfigurationDescriptor = McpToolDescriptor(
    DELETE_TOOL_NAME,
    "Delete Run Configuration",
    """
    Delete a project-level run configuration: remove it from the IDE and delete the file holding it.

    **Do not delete the file yourself** — this tool is the writer. It refuses while the configuration is
    running (stop it first with `stop_run_configuration`) and while other configurations reference it
    (a before-launch step or a compound configuration): pass `force = true` to delete it **and** remove
    those references, which is reported back.

    Only project-level configurations can be deleted here. Templates and IDE-level configurations are
    out of scope and are refused with an explanation.
    """.trimIndent(),
    RUN_CONFIG_DEFINITION_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.delete_run_configuration",
    schema(
        properties = properties(
            "configurationName" to property("string", "Name of the configuration to delete."),
            "force" to property(
                "boolean",
                "Delete even when other configurations reference it, removing those references. Defaults to false.",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("configurationName"),
    ),
    schema(
        properties = properties(
            "configurationName" to property("string", "Name of the deleted configuration."),
            "storagePath" to property("string", "Absolute path of the file that held the configuration."),
            "removedReferences" to arrayOf(
                property("string", "Name of the configuration whose reference was removed."),
                "Configurations that referenced it; their references were removed (only with `force`).",
            ),
            "warnings" to WARNINGS_SCHEMA,
        ),
        required = setOf("configurationName", "storagePath", "removedReferences", "warnings"),
    ),
    annotations(readOnly = false, destructive = true, idempotent = false),
)
