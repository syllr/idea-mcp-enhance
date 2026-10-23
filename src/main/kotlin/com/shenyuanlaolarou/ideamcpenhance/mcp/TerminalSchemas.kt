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
    "tab" to property("string", "Terminal tab id. Pass it to `terminal_io` for later reads/writes."),
    "text" to property("string", "Output captured since the command started, cleaned and truncated."),
    "exitCode" to nullableProperty(
        "integer",
        "Exit code for local terminals; null for remote terminals.",
    ),
    "timedOut" to property(
        "boolean",
        "true when `timeoutMs` elapsed before the command finished; `text` is then partial.",
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
    Do not use it for global commands such as `git`, `ls`, `cat`, `curl`, `docker` or `ps`;
    use the shell tool instead.

    Every call creates a new terminal tab. If you need to continue an existing tab, call
    `list_terminals` and then `terminal_io`; do not create another tab just to run a follow-up
    command in the same shell.

    `timeoutMs` (default 10000): `0` sends the command and returns immediately; `> 0` waits up
    to N ms for completion (max 12000). `input` is written once immediately after the command;
    include the newline yourself.

    The tab is a real interactive terminal: users can see the command and answer prompts. The
    tool fails with instructions when the Terminal tool window is missing, the engine is not
    "Reworked 2025", or shell integration is disabled.
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
            "timeoutMs" to property(
                "integer",
                "0 = return immediately; > 0 = wait up to N ms for the command to finish (max 12000). Default 10000.",
            ),
            "maxChars" to property("integer", MAX_CHARS_DESCRIPTION),
            "workingDirectory" to property("string", "Working directory; defaults to the project base path."),
            "env" to ENV_SCHEMA,
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
    Run a command in a real SSH terminal tab on a remote host and return its output.

    The host and credentials come from a project-level Deployment server that supports
    SFTP/SSH; pass its name as `serverName`. Every call creates a new remote tab. To continue
    an existing tab, call `list_terminals` and use `terminal_io` with `target: "remote"`.

    `loginShell` (default true) wraps the command in the user's login shell so PATH and tool
    managers are loaded. `input` is written once immediately after the command; include the
    newline yourself.

    `timeoutMs` (default 10000): `0` sends the command and returns immediately; `> 0` waits
    for stable output (max 12000). Remote shells have no command-finished signal: `exitCode`
    is always null, and `running` is best-effort (may be null).

    Host keys: `strictHostKey=false` (default) accepts and records a new key; `true` requires
    an existing known host key. No dialog is ever shown. The tool fails with instructions when
    the Deployment server, credentials or host key check is not usable.
    """.trimIndent(),
    TERMINAL_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.execute_in_remote_host",
    schema(
        properties = properties(
            "serverName" to property(
                "string",
                "Deployment server name, as reported by `list_deployment_servers`.",
            ),
            "command" to property("string", "Shell command to run on the remote host."),
            "input" to property(
                "string",
                "Text written once right after the command, e.g. \"y\\n\". Include the newline yourself.",
            ),
            "timeoutMs" to property(
                "integer",
                "0 = return immediately; > 0 = wait up to N ms for stable output (max 12000). Default 10000.",
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
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("serverName", "command"),
    ),
    schema(properties = TERMINAL_RUN_PROPERTIES, required = setOf("tab", "text", "exitCode", "timedOut", "running", "truncated", "warning")),
    annotations(readOnly = false, destructive = true, idempotent = false, openWorld = true),
)

// ---------------------------------------------------------------------------
// 3. terminal_io
// ---------------------------------------------------------------------------

internal const val TERMINAL_IO_TOOL_NAME = "terminal_io"

internal val terminalIoDescriptor = McpToolDescriptor(
    TERMINAL_IO_TOOL_NAME,
    "Terminal IO",
    """
    Read from or write to a terminal tab created by `project_sdk_terminal` or
    `execute_in_remote_host`.

    Use `list_terminals` to discover tab ids. Only ids returned by those tools with
    `addressable: true` are accepted; tabs opened manually by the user are not controllable.
    `target` must match how the tab was created (`local` or `remote`).

    `action: "read"`: `tail: N` returns the last N command segments (oldest first); without
    `tail` it returns new text since the previous read; `full: true` returns the whole buffer;
    `timeoutMs > 0` waits for stable output before reading.

    `action: "write"`: `text` is sent exactly as typed; include the newline yourself. Writing
    is input to the current command, not a new command segment.

    Remote terminals are best-effort: `exitCode` is null, and `running` may be null when
    completion cannot be detected.
    """.trimIndent(),
    TERMINAL_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.terminal_io",
    schema(
        properties = properties(
            "target" to property("string", "local for project_sdk_terminal tabs, remote for execute_in_remote_host tabs."),
            "tab" to property("string", "MCP tab id returned by `project_sdk_terminal` / `execute_in_remote_host`; user tabs have no id."),
            "action" to property("string", "read or write."),
            "text" to property("string", "Text to write; required when action = write."),
            "tail" to property("integer", "Return the last N command segments instead of the incremental text."),
            "full" to property("boolean", "Return the whole terminal buffer (read only)."),
            "timeoutMs" to property("integer", "For read: wait up to N ms for stable output (max 12000). Default 0."),
            "maxChars" to property("integer", MAX_CHARS_DESCRIPTION),
            "projectPath" to projectPathProperty(),
        ),
        required = setOf("target", "tab", "action"),
    ),
    schema(properties = TERMINAL_READ_PROPERTIES, required = setOf("tab", "running", "truncated")),
    annotations(readOnly = false, destructive = false, idempotent = false, openWorld = true),
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
                "MCP tab id for terminal_io; null for a tab the user opened manually.",
            ),
            "kind" to property("string", "mcp_project_sdk, mcp_remote, or user."),
            "addressable" to property("boolean", "true when terminal_io can target this tab."),
            "title" to nullableProperty("string", "Visible tab title."),
            "status" to property("string", "running, idle, not_started, terminated, closed, or unknown."),
            "shell" to nullableProperty("string", "Shell basename, e.g. zsh or bash."),
            "workingDirectory" to nullableProperty("string", "Working directory requested when the tab was created."),
            "currentDirectory" to nullableProperty("string", "Directory the terminal reports now."),
            "serverName" to nullableProperty("string", "Deployment server name for remote tabs."),
            "lastCommand" to nullableProperty("string", "Last command sent through this plugin."),
            "lastCommandAt" to nullableProperty("integer", "Epoch milliseconds of the last command."),
            "lastExitCode" to nullableProperty("integer", "Exit code of the last command; null for remote tabs."),
            "lastTimedOut" to nullableProperty("boolean", "Whether the last command hit its timeout."),
            "createdAt" to nullableProperty("integer", "Epoch milliseconds when this tab was created."),
            "lastUsedAt" to nullableProperty("integer", "Epoch milliseconds of the last MCP run/read/write."),
        ),
    )
    put(
        "required",
        buildJsonArray {
            listOf(
                "tab", "kind", "addressable", "title", "status", "shell",
                "workingDirectory", "currentDirectory", "serverName", "lastCommand",
                "lastCommandAt", "lastExitCode", "lastTimedOut", "createdAt", "lastUsedAt",
            ).forEach { add(JsonPrimitive(it)) }
        },
    )
}

internal val listTerminalsDescriptor = McpToolDescriptor(
    LIST_TERMINALS_TOOL_NAME,
    "List Terminals",
    """
    List terminal tabs in this project so you can choose one for `terminal_io`.

    Kinds:
    - `mcp_project_sdk`: created by `project_sdk_terminal`.
    - `mcp_remote`: created by `execute_in_remote_host`; includes `serverName`.
    - `user`: opened manually, or by a terminal-executed run configuration; `tab` is null.

    Only entries with a non-null `tab` and `addressable: true` can be passed to `terminal_io`.
    User tabs are listed for visibility only and are never controllable. Never use a title as
    an id: ids are registry keys for this IDE session and survive only while the tab is alive.

    Select by `kind`, `serverName`, `workingDirectory` and `lastCommand`; prefer the newest
    `lastUsedAt`. If none matches, create a new tab with `project_sdk_terminal` or
    `execute_in_remote_host`.

    Run configurations have a separate lifecycle. Use `list_running_configurations` /
    `inspect_run_configuration` for them. With `EXECUTE_IN_TERMINAL=true`, their terminal may
    appear here as a `user` tab, but it must still be controlled through the Run tools. With
    `EXECUTE_IN_TERMINAL=false`, it runs in the Run tool window and does not appear here.

    `includeUnmanaged: false` returns only addressable MCP tabs. `kind` filters `all`,
    `mcp_project_sdk`, `mcp_remote` or `user`.
    """.trimIndent(),
    TERMINAL_CATEGORY,
    "com.shenyuanlaolarou.ideamcpenhance.mcp.list_terminals",
    schema(
        properties = properties(
            "includeUnmanaged" to property(
                "boolean",
                "Default true. Include non-MCP tabs: user-opened tabs and terminal-executed run configurations.",
            ),
            "kind" to property(
                "string",
                "Filter by kind: all (default), mcp_project_sdk, mcp_remote, or user.",
            ),
            "projectPath" to projectPathProperty(),
        ),
    ),
    schema(
        properties = properties(
            "terminals" to arrayOf(TERMINAL_TAB_PROPERTIES, "Terminal tabs, MCP-created entries first."),
            "total" to property("integer", "Number of returned tabs."),
        ),
        required = setOf("terminals", "total"),
    ),
    annotations(readOnly = true, destructive = false, idempotent = true, openWorld = false),
)
