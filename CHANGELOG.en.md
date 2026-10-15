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

## [Unreleased]

## [0.0.1] - 2026-10-14

### Added

- **Copy as Prompt** — copy the selected code together with its file path and line range, ready to paste into
  any chat.
- **Copy Diagnostics as Prompt** — copy every error and warning of the current file as one clean list.
- **Run Lifecycle MCP tools** (need the bundled MCP Server plugin; IntelliJ IDEA 2026.2 or newer):
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
- IntelliJ IDEA 2026.2 (build 262) or newer is required for the MCP tools.
