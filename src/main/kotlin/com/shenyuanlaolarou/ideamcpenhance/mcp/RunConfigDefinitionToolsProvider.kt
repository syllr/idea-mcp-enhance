package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add

/**
 * 定义态工具：看 Run Configuration 的定义、命名解析、历史运行日志。
 *
 * 与运行态（`list` / `stop` / `rerun`）分开的理由：那一半回答"现在什么在跑"，
 * 这一半回答"这个配置是什么、上次为什么挂了"。工具分开，agent 少一次选择。
 *
 * 只读：配置的新建 / 修改 / 删除回到 IDE 界面做 —— 可重复的流程走 job skill，
 * 不再由 agent 代写 `.run/` 下的 xml 文件。
 */
class RunConfigDefinitionToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(
        InspectRunConfigurationTool(),
    )
}

/** 平台在"类型插件没装"时给出的类型 id。 */
private const val UNKNOWN_CONFIGURATION_TYPE_ID = "Unknown"

/** `history[]` 默认返回多少条。 */
internal const val DEFAULT_HISTORY_LIMIT = 20

/** 每条前置任务默认挂多少条自己的历史运行。 */
internal const val BEFORE_RUN_TASK_HISTORY_LIMIT = 5

// ---------------------------------------------------------------------------
// inspect_run_configuration
// ---------------------------------------------------------------------------

private class InspectRunConfigurationTool : BaseRunTool() {

    override val descriptor: McpToolDescriptor = inspectRunConfigurationDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return noProjectResult(INSPECT_TOOL_NAME)
        val name = arguments.requiredString("configurationName") ?: return missingNameError()
        val settings = findSettingsByName(project, name) ?: return unknownConfiguration(project, name)
        val historyLimit = arguments.optionalInt("historyLimit", DEFAULT_HISTORY_LIMIT)
            .let { if (it <= 0) DEFAULT_HISTORY_LIMIT else it }

        val definition = readRunConfigurationDefinition(project, settings)
        val storage = runConfigurationStorageOf(settings)
        val liveInstances = snapshotOf(project, settings).instances.filter { it.running }
        // 同一个 run 不能既算"活实例"又算"历史"：历史是"已经结束的那些"，
        // 用日志路径做去重键（一次运行一个文件）。
        val liveLogs = liveInstances.mapNotNull { it.logFile }.toSet()
        // 插件没装时平台会把类型解析成 `Unknown`，但文件里的 `type` 属性还在 —— 那个才是这个配置
        // 真正声明的类型（别的机器上装了插件就能跑）。所以这里优先用文件里的值。
        val declaredType = readRunConfigurationElement(project, settings)?.getAttributeValue("type")
        val typeKnown = settings.type.id != UNKNOWN_CONFIGURATION_TYPE_ID
        val ownLogs = listRunLogs(project, settings.name)
        // 父配置最近一次运行的开始时刻：Before launch 只可能发生在这之后，
        // 更早的那些属于上一次运行，混进来只会误导。
        val parentStartedAt = ownLogs.maxOfOrNull { it.startedAtMillis }
        val inspection = RunConfigurationInspection(
            configurationName = settings.name,
            typeId = if (typeKnown) settings.type.id else declaredType ?: settings.type.id,
            typeDisplayName = if (typeKnown) settings.type.displayName else "Unknown (plugin not installed)",
            typeKnown = typeKnown,
            storage = storage,
            storagePath = runConfigurationFileOf(project, settings)?.toString(),
            shared = settings.isShared,
            isTemplate = settings.isTemplate,
            canRun = cannotRunReason(settings, null) == null,
            canRunBlockedReason = cannotRunReason(settings, null),
            naming = parseRunConfigurationName(settings.name),
            attributes = definition?.attributes.orEmpty(),
            options = definition?.options.orEmpty(),
            envs = definition?.envs.orEmpty(),
            beforeRunTasks = beforeRunTasksWithHistory(
                project = project,
                steps = definition?.beforeRunTasks.orEmpty(),
                parentStartedAt = parentStartedAt,
            ),
            configurationXml = readRunConfigurationElement(project, settings)?.let(::writeConfigurationXml),
            instances = liveInstances,
            history = ownLogs
                .filterNot { it.logFile in liveLogs }
                .take(historyLimit),
            warnings = warningsFor(settings, storage, definition),
        )
        return McpToolCallResult.text(summaryOf(inspection), inspection.toJson())
    }

    /**
     * 给每条前置任务挂上「被挂配置最近的运行」。
     *
     * Before launch 跑的是一条**独立的** Run Configuration，它有自己的日志键与历史；
     * 父配置的 `history[]` 里永远看不到它。不把这条链显式连起来，agent 就只能去翻临时目录猜。
     *
     * @param parentStartedAt 父配置最近一次运行的开始时刻；null（从没跑过）时不做时间过滤
     */
    private fun beforeRunTasksWithHistory(
        project: Project,
        steps: List<RunBeforeLaunchStep>,
        parentStartedAt: Long?,
    ): List<RunBeforeLaunchTask> = steps.map { step ->
        val name = step.configurationName
        val runs = if (name.isNullOrBlank()) {
            emptyList()
        } else {
            listRunLogs(project, name)
                .filter { parentStartedAt == null || it.startedAtMillis >= parentStartedAt }
                .take(BEFORE_RUN_TASK_HISTORY_LIMIT)
        }
        RunBeforeLaunchTask(step = step, recentRuns = runs)
    }

    /**
     * 只在**需要 agent 动手**时才给 warning。
     *
     * "元数据缺失"、"日志已被清理"这类正常降级一律不说 —— 安静降级，不占注意力。
     */
    private fun warningsFor(
        settings: RunnerAndConfigurationSettings,
        storage: RunConfigurationStorage,
        definition: RunConfigurationDefinition?,
    ): List<String> = buildList {
        if (storage != RunConfigurationStorage.RUN_FILE) {
            add(
                "This configuration is not stored in `.run/`, so the definition tools do not manage it: " +
                    "edit it in the IDE instead. Its live state and logs are still reported here.",
            )
        }
        if (definition?.options?.get("EXECUTE_IN_TERMINAL") == "true") {
            add(
                "This shell configuration runs inside the IDE terminal. The platform gives it no process " +
                    "handle, so there is no log to capture and `stop_run_configuration` / " +
                    "`rerun_run_configuration` cannot reach it. Use \"Execute in terminal\" = false to make " +
                    "it manageable.",
            )
        }
        if (settings.isTemplate) {
            add("This is a run configuration template, not a configuration: it cannot be run, changed or deleted.")
        }
    }

    /** 一行摘要：类型 + 存储位置 + 能不能跑 + 当前实例数 + 历史条数。 */
    private fun summaryOf(inspection: RunConfigurationInspection): String {
        val storage = when (inspection.storage) {
            RunConfigurationStorage.RUN_FILE -> "stored in .run/"
            RunConfigurationStorage.DOT_IDEA -> "stored in .idea/runConfigurations/"
            RunConfigurationStorage.WORKSPACE -> "stored in the workspace (not shared)"
            RunConfigurationStorage.TEMPORARY -> "temporary (not saved)"
            RunConfigurationStorage.UNKNOWN -> "storage unknown"
        }
        val runnable = if (inspection.canRun) "can run" else "cannot run yet"
        val beforeRun = inspection.beforeRunTasks
        val beforeRunText = if (beforeRun.isEmpty()) {
            ""
        } else {
            val names = beforeRun.joinToString(", ") { it.step.configurationName ?: it.step.taskKind ?: "?" }
            ", ${beforeRun.size} before-launch step(s) [$names]"
        }
        return "${inspection.configurationName} (${inspection.typeDisplayName}): $storage, $runnable, " +
            "${inspection.instances.size} live instance(s), ${inspection.history.size} earlier run(s)$beforeRunText."
    }
}
