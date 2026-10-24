package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 终端工具的对外契约：名字、说明、输入 schema、输出 schema、行为注解。
 *
 * 名字/描述是 agent 决定「该不该调、参数怎么填」的唯一依据；输出 schema 必须和
 * `TerminalModels` 的 `toJson()` 逐字段对齐。
 */

internal val TERMINAL_CATEGORY = McpToolCategory(
    "Terminal",
    "com.shenyuanlaolarou.ideamcpenhance.mcp.Terminal",
    false,
    false,
)

// ---------------------------------------------------------------------------
// 共享 schema 片段
// ---------------------------------------------------------------------------

private const val MAX_CHARS_DESCRIPTION =
    "Maximum characters of output to return (default 30000, max 200000). Truncation keeps the newest output."

private val ENV_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("Extra environment variables for the command."))
    put(
        "additionalProperties",
        buildJsonObject { put("type", JsonPrimitive("string")) },
    )
}

private val COMMAND_SEGMENT_SCHEMA: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("description", JsonPrimitive("One executed command together with its output."))
    put(
        "properties",
        properties(
            "command" to property("string", "The command line as it was sent to the terminal."),
            "output" to property("string", "The command's output, with the command echo and trailing prompt removed."),
            "exitCode" to nullableProperty(
                "integer",
                "Exit code when the platform can provide one; null for remote terminals.",
            ),
        ),
    )
    put(
        "required",
        buildJsonArray {
            add(JsonPrimitive("command"))
            add(JsonPrimitive("output"))
            add(JsonPrimitive("exitCode"))
        },
    )
}

private val COMMANDS_SCHEMA: JsonObject = buildJsonObject {
    put(
        "type",
        buildJsonArray {
            add(JsonPrimitive("array"))
            add(JsonPrimitive("null"))
        },
    )
    put("description", JsonPrimitive("Command segments, oldest first. Null when `text` is returned instead."))
    put("items", COMMAND_SEGMENT_SCHEMA)
}

private val TERMINAL_RUN_PROPERTIES: JsonObject = properties(
    "tab" to property("string", "Terminal tab id. Pass it to `terminal_read` / `terminal_interrupt` later."),
    "text" to property("string", "Output captured since the command started, cleaned and truncated."),
    "exitCode" to nullableProperty(
        "integer",
        "Exit code for local terminals; null for remote terminals.",
    ),
    "timedOut" to property(
        "boolean",
        "true when this call stopped waiting before the command finished; `text` is then partial — " +
            "read the tab with `terminal_read` for the rest.",
    ),
    "running" to nullableProperty(
        "boolean",
        "true when the command is still running; null when the platform cannot tell.",
    ),
    "truncated" to property(
        "boolean",
        "true when the output was cut by the terminal scrollback or the tool's size limit.",
    ),
    "warning" to nullableProperty(
        "string",
        "Warning when the command may break shell tracking (for example `exec`).",
    ),
)

private val TERMINAL_READ_PROPERTIES: JsonObject = properties(
    "tab" to property("string", "Terminal tab id."),
    "text" to nullableProperty(
        "string",
        "New output since the previous read, or the whole buffer when `full` is true.",
    ),
    "commands" to COMMANDS_SCHEMA,
    "running" to nullableProperty(
        "boolean",
        "true when a command is still running; null when the platform cannot tell.",
    ),
    "truncated" to property(
        "boolean",
        "true when the terminal scrollback or the tool's size limit cut the text.",
    ),
)

// ---------------------------------------------------------------------------
// 1. project_sdk_terminal
// ---------------------------------------------------------------------------

internal const val PROJECT_SDK_TERMINAL_TOOL_NAME = "project_sdk_terminal"

internal val projectSdkTerminalDescriptor = McpToolDescriptor(
    PROJECT_SDK_TERMINAL_TOOL_NAME,
    "Project SDK Terminal",
    """
    Run a command in this project's SDK terminal and return its output.

    Use it for project-bound commands that need the IDE's project environment: Maven, Gradle,
    Node / package managers, project scripts, dev servers, code generators and migrations.
    Global commands (`git`, `ls`, `cat`, `curl`, `docker`, `ps`) belong to the shell tool.

    The command runs in this project's default tab, and that tab is reused for the next command
    too: `cd`, exported variables and background jobs stay in place, so one `cd ... &&` is enough
    for the whole session. The first call creates that default tab; `list_terminals`
    reports it as the group's `default`. Pass `tab` to target a specific one; `newTab: true` opens an
    extra shell without taking over the default. `env` forces a new tab because it can only take
    effect when the shell starts; `workingDirectory` is applied as a `cd` inside the reused tab.

    If the command runs long, this call stops waiting and returns `running: true`; the command
    keeps running in that tab — call `terminal_read` to see the rest.
    `input` is written once immediately after the command; include the newline yourself.
    """.trimIndent(),
    TERMINAL_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.project_sdk_terminal",
    schema(
        properties = properties(
            "command" to property("string", "Command to run in this project's SDK terminal."),
            "input" to property(
                "string",
                "Text written once right after the command, e.g. \"y\\n\". Include the newline yourself.",
            ),
            "maxChars" to property("integer", MAX_CHARS_DESCRIPTION),
            "workingDirectory" to property("string", "Working directory; defaults to the project base path."),
            "env" to ENV_SCHEMA,
            "tab" to property(
                "string",
                "Reuse this tab instead of picking one. Omit to reuse the most recently used idle tab of this " +
                    "project.",
            ),
            "newTab" to property(
                "boolean",
                "Default false. true = always open a fresh tab (independent task, or the previous command is " +
                    "still running).",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("command"),
    ),
    schema(properties = TERMINAL_RUN_PROPERTIES, required = setOf("tab", "text", "exitCode", "timedOut", "running", "truncated", "warning")),
    annotations(readOnly = false, destructive = true, idempotent = false, openWorld = true),
)

// ---------------------------------------------------------------------------
// 2. execute_in_remote_host
// ---------------------------------------------------------------------------

internal const val EXECUTE_REMOTE_TOOL_NAME = "execute_in_remote_host"

internal val executeInRemoteHostDescriptor = McpToolDescriptor(
    EXECUTE_REMOTE_TOOL_NAME,
    "Execute in Remote Host",
    """
    Run a command in a real SSH terminal tab on a remote host.

    WHEN: the work happens on a remote machine — a deployment / test / development host, or any
    machine the user names. This tool IS the way there: host, credentials, jump host and port
    forwarding all come from the IDE's Deployment server.

    WHICH HOST: `serverName` is the server display name (not its hostname) and must be one of
    `servers[].name` from `list_server_deployments`. Every server it reports can run commands,
    even one with zero deployments.

    REUSE THE TAB: the command runs in that server's default tab, and that tab is reused for the
    next command too — working directory, exported variables and background jobs stay in place, so
    drop the `cd ... &&` prefix. The first call creates that default tab; `list_terminals` reports
    it as the group's `default`. Pass `tab` to target a specific one, or `newTab: true` for an extra
    shell that does not take over the default. A reused tab keeps its own cwd: pass
    `workingDirectory` only to move it.

    `loginShell` (default true) loads PATH and tool managers. `input` is written once after the
    command; include the newline. If the command runs long, this call stops waiting and returns
    `running: true`; use `terminal_read` to see the rest. Remote shells have no command-finished
    signal: `exitCode` is always null and `running` is best-effort.

    `strictHostKey=false` (default) accepts and records a new host key; `true` requires a known one.
    """.trimIndent(),
    TERMINAL_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.execute_in_remote_host",
    schema(
        properties = properties(
            "serverName" to property(
                "string",
                "Server display name, as reported by `servers[].name` from `list_server_deployments`. " +
                    "Not a hostname.",
            ),
            "command" to property("string", "Shell command to run on the remote host."),
            "input" to property(
                "string",
                "Text written once right after the command, e.g. \"y\\n\". Include the newline yourself.",
            ),
            "maxChars" to property("integer", MAX_CHARS_DESCRIPTION),
            "workingDirectory" to property("string", "Remote working directory; defaults to the login directory."),
            "loginShell" to property(
                "boolean",
                "Default true. Wrap the command in the user's login shell (zsh -lic / bash -lc) so PATH and tool managers are loaded.",
            ),
            "strictHostKey" to property(
                "boolean",
                "Default false. true = reject a host key that is not already in known_hosts; false = accept a new key and record it (accept-new).",
            ),
            "tab" to property(
                "string",
                "Reuse this tab instead of picking one. Omit to reuse the most recently used idle tab of the same " +
                    "server.",
            ),
            "newTab" to property(
                "boolean",
                "Default false. true = always open a fresh tab (independent task, or the previous command is " +
                    "still running).",
            ),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("serverName", "command"),
    ),
    schema(properties = TERMINAL_RUN_PROPERTIES, required = setOf("tab", "text", "exitCode", "timedOut", "running", "truncated", "warning")),
    annotations(readOnly = false, destructive = true, idempotent = false, openWorld = true),
)

// ---------------------------------------------------------------------------
// 3. terminal_read
// ---------------------------------------------------------------------------

internal const val TERMINAL_READ_TOOL_NAME = "terminal_read"

internal val terminalReadDescriptor = McpToolDescriptor(
    TERMINAL_READ_TOOL_NAME,
    "Terminal Read",
    """
    Read an existing terminal tab — an instant snapshot, it never waits.

    Its main job: `project_sdk_terminal` / `execute_in_remote_host` returned `running: true`
    because the command was still going. Call it again every few seconds until `running` is false.

    `target` must match how the tab was created: "local" for `project_sdk_terminal` tabs,
    "remote" for `execute_in_remote_host` tabs. Get tab ids from `list_terminals`; only tabs with
    `addressable: true` can be read.

    `tail: N` returns the last N command segments (oldest first, with their exit codes);
    without `tail` it returns the text added since the previous read; `full: true` returns the
    whole buffer. Remote terminals are best-effort: `exitCode` is null, and `running` may be
    null when completion cannot be detected.
    """.trimIndent(),
    TERMINAL_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.terminal_read",
    schema(
        properties = properties(
            "target" to property("string", "local for project_sdk_terminal tabs, remote for execute_in_remote_host tabs."),
            "tab" to property("string", "MCP tab id returned by `project_sdk_terminal` / `execute_in_remote_host`; user tabs have no id."),
            "tail" to property("integer", "Return the last N command segments instead of the incremental text."),
            "full" to property("boolean", "Return the whole terminal buffer."),
            "maxChars" to property("integer", MAX_CHARS_DESCRIPTION),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("target", "tab"),
    ),
    schema(properties = TERMINAL_READ_PROPERTIES, required = setOf("tab", "running", "truncated")),
    annotations(readOnly = true, destructive = false, idempotent = true, openWorld = false),
)

// ---------------------------------------------------------------------------
// 4. terminal_interrupt
// ---------------------------------------------------------------------------

internal const val TERMINAL_INTERRUPT_TOOL_NAME = "terminal_interrupt"

internal val terminalInterruptDescriptor = McpToolDescriptor(
    TERMINAL_INTERRUPT_TOOL_NAME,
    "Terminal Interrupt",
    """
    Send Ctrl-C to an existing terminal tab — the only way to stop a command that is still running.

    Use it when a command hangs: no prompt comes back, or it runs far longer than it should.
    The execute tools cannot help — they wait for an idle shell, so a hung command blocks them.

    It sends exactly one Ctrl-C and accepts no text, so it can only interrupt, never run anything.
    Read the tab afterwards with `terminal_read`.
    """.trimIndent(),
    TERMINAL_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.terminal_interrupt",
    schema(
        properties = properties(
            "target" to property("string", "local for project_sdk_terminal tabs, remote for execute_in_remote_host tabs."),
            "tab" to property("string", "MCP tab id returned by `project_sdk_terminal` / `execute_in_remote_host`; user tabs have no id."),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("target", "tab"),
    ),
    schema(properties = TERMINAL_READ_PROPERTIES, required = setOf("tab", "running", "truncated")),
    annotations(readOnly = false, destructive = true, idempotent = true, openWorld = false),
)

// ---------------------------------------------------------------------------
// 4. list_terminals
// ---------------------------------------------------------------------------

internal const val LIST_TERMINALS_TOOL_NAME = "list_terminals"

private val TERMINAL_TAB_PROPERTIES: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put(
        "properties",
        properties(
            "tab" to nullableProperty(
                "string",
                "MCP tab id for terminal_read / terminal_interrupt; null for a tab the user opened manually.",
            ),
            "kind" to property("string", "mcp_project_sdk, mcp_remote, or user."),
            "addressable" to property("boolean", "true when terminal_read / terminal_interrupt can target this tab."),
            "status" to property("string", "running, idle, not_started, terminated, closed, or unknown."),
            "shell" to nullableProperty("string", "Shell basename, e.g. zsh or bash."),
            "workingDirectory" to nullableProperty("string", "Working directory requested when the tab was created."),
            "currentDirectory" to nullableProperty("string", "Directory the terminal reports now."),
            "serverName" to nullableProperty("string", "Server display name for remote tabs."),
            "lastCommand" to nullableProperty("string", "Last command sent through this plugin."),
            "lastCommandAt" to nullableProperty("integer", "Epoch milliseconds of the last command."),
            "lastExitCode" to nullableProperty("integer", "Exit code of the last command; null for remote tabs."),
            "lastTimedOut" to nullableProperty("boolean", "Whether the last command was still running when its call stopped waiting."),
            "createdAt" to nullableProperty("integer", "Epoch milliseconds when this tab was created."),
            "lastUsedAt" to nullableProperty("integer", "Epoch milliseconds of the last MCP run/read/write."),
        ),
    )
    put(
        "required",
        buildJsonArray {
            listOf(
                "tab", "kind", "addressable", "status", "shell",
                "workingDirectory", "currentDirectory", "serverName", "lastCommand",
                "lastCommandAt", "lastExitCode", "lastTimedOut", "createdAt", "lastUsedAt",
            ).forEach { add(JsonPrimitive(it)) }
        },
    )
}

private val TERMINAL_SOURCE_PROPERTIES: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put(
        "properties",
        properties(
            "source" to property("string", "mcp_project_sdk or mcp_remote."),
            "server" to nullableProperty(
                "string",
                "Server display name. Present only when source is mcp_remote.",
            ),
            "default" to nullableProperty(
                "string",
                "The tab a command lands on when `tab` is omitted. Null = this source has not been used yet.",
            ),
            "tabs" to arrayOf(TERMINAL_TAB_PROPERTIES, "Tabs belonging to this source."),
        ),
    )
    put(
        "required",
        buildJsonArray {
            listOf("source", "default", "tabs").forEach { add(JsonPrimitive(it)) }
        },
    )
}

internal val listTerminalsDescriptor = McpToolDescriptor(
    LIST_TERMINALS_TOOL_NAME,
    "List Terminals",
    """
    List terminal tabs in this project, grouped by source so the default tab of each is explicit.

    Sources:
    - `mcp_project_sdk`: created by `project_sdk_terminal`; one group, one default.
    - `mcp_remote`: created by `execute_in_remote_host`; one group per server (`server` names it),
      each with its own default.

    Each group reports `default` — the tab a command lands on when `tab` is omitted. A group with
    no default has not been used yet: the next execute call creates one.

    Only entries with a non-null `tab` and `addressable: true` can be passed to `terminal_read` / `terminal_interrupt`.
    The `tab` value is the id: a registry key for this IDE session that survives only while the tab is alive.

    Run configurations have a separate lifecycle: use `list_running_configurations` /
    `inspect_run_configuration`; a configuration executed in the terminal does not appear here.

    Only MCP tabs are listed: tabs the user opened cannot be targeted, so they never appear.
    `kind` filters `all`, `mcp_project_sdk` or `mcp_remote`.
    """.trimIndent(),
    TERMINAL_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.list_terminals",
    schema(
        properties = properties(
            "kind" to property(
                "string",
                "Filter by kind: all (default), mcp_project_sdk, or mcp_remote.",
            ),
            "projectPath" to projectPathProperty(),
        ),
    ),
    schema(
        properties = properties(
            "sources" to arrayOf(
                TERMINAL_SOURCE_PROPERTIES,
                "One entry per source: local, each remote server, and user tabs.",
            ),
        ),
        required = setOf("sources"),
    ),
    annotations(readOnly = true, destructive = false, idempotent = true, openWorld = false),
)
