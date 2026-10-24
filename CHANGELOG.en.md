<!--
  Marketplace-facing English changelog: the <change-notes> field of plugin.xml is rendered from
  the section of the current version in this file (see build.gradle.kts).

  Why a second file: the plugin store page is English-only (JetBrains Marketplace requires English
  as the primary language), while the repository changelog CHANGELOG.md stays Chinese. Both must get
  a section for every release — a missing section here fails the build instead of shipping empty or
  Chinese change notes.

  面向 JetBrains Marketplace 的英文变更日志。<change-notes> 只从这里取当前版本段落；
  仓库内部的中文变更日志见 CHANGELOG.md。发版时两个文件都要加一段。
-->

# IDE MCP Enhance Changelog

## [0.1.4] - 2026-10-24

### Added

- **One default tab per source**: with no `tab`, `project_sdk_terminal` / `execute_in_remote_host` land on the
  source's **default tab** — one for local, one per remote server. The first call creates it; a shell opened with
  `newTab: true` does not take over that role, and if the default tab is closed the next call recreates one.
  `list_terminals` groups by source and reports it as that group's `default`, so where a command goes is
  predictable instead of drifting with usage history.
- **Busy / server guards**: a tab whose previous command is still running (or timed out) is not reused, and
  a `tab` that belongs to another server is rejected with `tab_server_mismatch`.

### Changed

- **Every wait budget is the tool's fixed behaviour, not a parameter**: `project_sdk_terminal` /
  `execute_in_remote_host` lost `timeoutMs` (fixed at 300s; past it the call returns `running: true` with the same
  tab), and `upload_to_deployment` / `download_from_deployment` lost `waitMs`,
  `stop_run_configuration` lost `waitTimeoutMs`, `rerun_run_configuration` lost `stopTimeoutMs` /
  `startTimeoutMs`. Every wait budget is now a fixed 300s; when that is unacceptable, use
  `waitForStart = false` or poll the returned `handle`.
- **Busy tabs are waited out**: instead of failing with `terminal_tab_busy` the moment the previous command is
  still running, the tools back off 250ms → 500ms → then 1000ms each time until the shell reaches its prompt
  (no upper bound); only a tab that disappears during the wait is treated as an error.
- **`terminal_read` incremental output**: when a command ran between two reads, the second read replayed the whole
  history (the execute path advanced the read cursor with a different output model's offsets). It now returns only
  what is new.
- `terminal_io` is split into two single-purpose tools: `terminal_read` (instant snapshot, takes no timeout —
  use it to check the previous command after its 300s timeout) and `terminal_interrupt` (sends exactly one
  Ctrl-C to stop a hung command; it accepts no text, so it cannot become a hidden execution channel). `write`
  is gone — appending a command to a finished shell is covered by `input` plus tab reuse.
- Remote tabs are now named `mcp-<serverName>-<n>` (for example `mcp-mbpmiddleware-1`) instead of
  `MCP-R-<n>`, numbered per server.
- Commands sent to a reused remote tab are no longer wrapped in `zsh -lic` / `exec`, so `cd` and background
  jobs stay in the session.

### Fixed

- `buildRemoteCommand` no longer emits `; exec <command>`: with `loginShell=false` it replaced the tab's
  interactive shell, so the tab died as soon as the command finished.

## [0.1.3] - 2026-10-23

### Added

- **Four terminal MCP tools**:
  - `project_sdk_terminal` runs commands in the project's SDK terminal and returns the output. Each call
    creates a real Terminal tab and supports `input`, `timeoutMs`, `maxChars`, `workingDirectory` and
    `env`.
  - `execute_in_remote_host` opens a real SSH terminal through a Deployment SFTP/SSH server and supports
    `loginShell`, `strictHostKey`, `workingDirectory` and interactive input.
  - `terminal_io` reads incremental output, `tail` command segments or the `full` buffer from an existing
    local/remote tab, and writes input to it.
  - `list_terminals` lists `mcp_project_sdk`, `mcp_remote` and `user` tabs. Only MCP tabs with a tab id and
    `addressable: true` can be passed to `terminal_io`.
- **Project terminal environment injection**: Maven home, Node runtime/package manager, Go SDK
  (`GOROOT` / `GOPATH` / `GOBIN`) and Python SDK / venv are derived from IDE settings and added to the
  Terminal environment. Explicit tool `env` values win.
- **Per-project terminal session registry** for local command-block offsets, remote command markers, tab
  kind and last-command state, enabling incremental reads and closed-tab detection.
- **Built-in `execute_terminal_command` filter**: a `McpToolFilterProvider` hides the overlapping built-in
  tool in favour of `project_sdk_terminal`.

### Changed

- Terminal error results now include the machine-readable code in the text as `[code] message` while keeping
  the structured error payload.
- Remote preflight checks host and Key pair key path before credentials, so `deployment_host_missing` and
  `deployment_key_missing` are no longer reported as missing credentials.
- New tab names only avoid tabs that are still alive; tabs left over from a previous IDE session do not
  affect naming.
- Remote `running` is best-effort: known for synchronous completion/timeout and `null` for asynchronous
  runs when completion cannot be detected. `exitCode` remains `null`.
- Renamed `list_deployment_servers` to `list_server_deployments`: it is now a server-centric list whose
  `deployments[]` contains only deployment names (derived from the local path; mapping paths are not
  expanded). A server may have no deployments; it can still be used by `execute_in_remote_host` but cannot
  upload/download. `upload_to_deployment` / `download_from_deployment` now select a target only by the
  globally unique `deploymentName`. Servers without explicit mappings
  are no longer reported as configured because of the platform's default mapping.
- Compressed the terminal, run-configuration and deployment tool descriptions; added the new-tab
  lifecycle, defaults, `list_terminals` entry point and async polling rules.

### Fixed

- Incremental reads, `tail` truncation warnings and closed-tab detection after local `clear` / `reset`.
- Remote host-key policy, unknown-host, network, authentication and missing-key error classification and
  Chinese guidance.
- Go `GOPATH` / `GOBIN` injection: prefer IDE/environment values and fall back to the Go SDK's `go env`.
- Node package-manager path detection for both executable-file and directory paths.
- Python terminal selection: prefer project/module SDK, then a registered project-directory SDK, then
  `.venv`.

### Notes

- Remote terminals never report an `exitCode`; asynchronous remote `running` may also be `null` because the
  remote shell has no command-finished signal.
- User-opened tabs are visible but not addressable, so `terminal_io` cannot accidentally write into a shell
  the user is using.
- Run configurations keep their own lifecycle. A configuration with `EXECUTE_IN_TERMINAL=true` may appear as
  a `user` tab in `list_terminals`, but must be controlled through the Run tools.
- Remote terminals require Ultimate's `remote-run` / Deployment plugins; local terminals require the
  Terminal plugin.

## [0.1.1] - 2026-10-20

### Removed

- **The `edit_file` tool** — its purpose (writes must go through the IDE, otherwise the stale editor copy is
  written back by `reformat_file`) is fully covered by `create_run_configuration` / `update_run_configuration`
  for configurations, and keeping a generic file-writing door only made "which way should this edit go"
  ambiguous. Change `.run/*.run.xml` through those definition tools.

## [0.1.0] - 2026-10-20

### Added

- **Run configuration definition tools (4)** — the built-in MCP server could list and run configurations but
  had no way to create, change or delete one:
  - `inspect_run_configuration` (read-only) — everything about one configuration: type, storage, whether it
    can run, `options` / `attributes` / `envs` / before-launch steps, the raw XML, live instances (pid, state,
    start time, log file), earlier runs (newest first), and the name parsed into platform / environment. Still
    works when the type's plugin is missing: `type` comes from the file, `typeKnown` is false.
  - `create_run_configuration` — create a configuration of **any type**. The body comes from `cloneFrom`
    (safest), `configurationXml`, or the IDE's template plus patches; it is validated through the platform's
    own `readExternal` before it is written, and misspelled fields are reported as `unknownOptions`.
  - `update_run_configuration` — patch fields (`options` / `attributes` / `envs`) **and rename** (`newName`):
    the file moves, references to it (before-launch steps, compound configurations) are rewritten, and its
    recorded run logs are migrated.
  - `delete_run_configuration` — refused while the configuration is running or referenced; `force = true`
    deletes it and removes those references.
- **Run logs and history**: output is captured from the moment the process starts into `ij_enhance_run__*.log`
  in the IDE temp directory, with a sidecar (pid, exit code, end time) written on exit.
  `list_running_configurations` and `inspect_run_configuration` report the log file per instance, and earlier
  runs are listed newest first, with only the ones whose log still exists.
- Instances in `list_running_configurations` now carry `state` (`running` / `terminating` / `starting`) and
  `logFile`.

### Changed

- `list_running_configurations` is now an **index**: derived counts, constant fields (`isTemplate`, `running`,
  `exitCode`) and the unreliable UI text (`displayName`) are gone. Details live in
  `inspect_run_configuration`.
- `update_run_configuration` now refreshes all three copies: the file on disk, the open editor document, and
  the IDE's in-memory configuration — "the file changed but Run still executes the old script" was a real bug.

### Fixed

- **Machine-specific defaults from the Shell template**: a newly created Shell configuration inherited an
  absolute working directory, `/bin/zsh`, terminal mode and `EXECUTE_SCRIPT_FILE=true`. They are now normalised
  to `$PROJECT_DIR$`, an empty interpreter, non-terminal mode, and `EXECUTE_SCRIPT_FILE` derived from whether
  `SCRIPT_TEXT` was given.
- **Types that deserialize via `XmlSerializer` (Maven, Gradle) could not be read**: whitespace text nodes made
  the platform throw `Text as Element`. The platform's `JDOMUtil` is used instead.
- **Stale editor copy after an update**: writing only to disk left the open document stale, so `read_file`
  returned the old content and saving it reverted the change. The document is updated now.
- **Log capture missed the `rerun` path**: our own callback called `startNotify()` first, which made the
  platform skip `processStarting`. Capture is attached before starting; runs started by hand are covered by a
  fallback in `processStarted`.
- Live runs no longer appear in the history twice (deduplicated by log file).
- When the type's plugin is missing (`Unknown`), cloning and updating are no longer blocked: the body is
  handled as-is with a warning.

### Removed

- **`create_shell_run_configuration`** — it was a subset of `create_run_configuration` (Shell only, no platform
  validation). Creating a Shell configuration now goes through the generic tool, so there is one implementation.

## [0.0.3] - 2026-10-17

### Fixed

- **The Deployment tools now find a server by its name**: pass the display name from `list_deployment_servers`
  (`servers[].name`) as `serverName`. It used to be compared against the server's id (a UUID), so **every**
  upload/download that named a server failed outright — with the self-contradictory
  `No deployment server named "X" … Existing: [X]`.
- **Path mappings are now read from what you configured in Deployment settings**: `mappings[]` in
  `list_deployment_servers`, and the local roots upload/download work on, are read by **server id**. The lookup
  used to pass the display name, so the platform returned a freshly fabricated "project root → server root"
  mapping every time — non-empty and perfectly plausible, while your sub-directories, multiple mappings and
  custom deploy paths were silently ignored.
- **A failed upload no longer reads like a success**: when the platform reports failure (it only returns a
  boolean, and the `failed` counter can be 0), `upload_to_deployment` and `get_deployment_task_status` used to
  print `Uploaded to "X": 0 operation(s) …`. They now say `FAILED before any operation ran` /
  `FAILED after N operation(s)` and point at the IDE's Deployment log for the cause.

### Changed

- The text summary of `list_deployment_servers` lists every server name, marking the default one with
  `(default)`, so a client can pick a `serverName` without reading structuredContent.
- The optional dependency on the Deployment plugin is now a **chained config-file**
  (`plugin.xml` → `mcpserver.xml` → `deployment.xml`): without that plugin the deployment tools are simply not
  registered and the rest of the plugin loads as usual. The former runtime existence check is gone — it used
  `PluginManager.findEnabledPlugin`, which is `@ApiStatus.Internal`.

### Removed

- A warning that could never fire ("has no path mapping … nothing to work on"): the platform materialises a
  default mapping for every server, so that list is practically never empty — and "empty means nothing to do"
  was not true anyway.

## [0.0.2] - 2026-10-17

### Added

- **Deployment MCP tools** (4 new — Ultimate only, see Notes):
  - `list_deployment_servers` — every deployment server this project can use, its path mappings, the project-wide
    deployment options, and warnings about dangerous combinations. It reads the IDE's stored settings only.
  - `upload_to_deployment` — upload the mapped directories. `mode: "mirror"` (the default) also deletes remote
    files the project no longer contains; `mode: "upload"` sends local files only and leaves remote extras alone.
  - `download_from_deployment` — pull the server's copy back into the mapped local roots.
  - `get_deployment_task_status` — poll one transfer by its handle; it never starts anything.
- **Handle-based async**: a transfer runs in the IDE's background and can outlive the tool's wait budget (10000ms
  by default). It then reports `state: "in_progress"` together with a handle that identifies _that_ transfer — not
  the server, which accumulates many transfers over time. One server never runs two transfers at once: a second
  call joins the running one and returns its handle.

### Notes

- The Deployment tools appear **only on Ultimate**: they rely on the bundled Deployment (FTP/SFTP/WebDAV
  Connectivity) plugin, which needs `com.intellij.modules.ultimate`. Without it these four tools are simply not
  registered and everything else keeps working.
- Transfers use the IDE's own transfer machinery, so the counts match the IDE's: `transferred` counts files, while
  `deleted` counts delete operations — removing a directory counts as one, however many files it held.

## [0.0.1] - 2026-10-14

### Added

- **Copy as Prompt** — copy the selected code together with its file path and line range, ready to paste into
  any chat.
- **Copy Diagnostics as Prompt** — copy every error and warning of the current file as one clean list.
- **Run Lifecycle MCP tools** (need the bundled MCP Server plugin; IntelliJ IDEA 2026.2):
  - `list_running_configurations` — what is actually running right now: which configurations, how many
    processes each, and with which process id.
  - `stop_run_configuration` — stop all running instances of a configuration and wait until they really
    exit.
  - `rerun_run_configuration` — the IDE's Rerun button: restart a configuration and report the new process,
    its output, and every step of the restart.
  - `create_shell_run_configuration` — create a project-level Shell Script run configuration.

### Notes

- The MCP tools drive the IDE's own process handlers: no port guessing, no command-line matching, and no
  changes to your start scripts.
- IntelliJ IDEA 2026.2 (build 262) is required for the MCP tools.
