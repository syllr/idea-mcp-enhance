package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
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
            beforeLaunchSteps = definition?.beforeLaunchSteps.orEmpty(),
            configurationXml = readRunConfigurationElement(project, settings)?.let(::writeConfigurationXml),
            instances = liveInstances,
            history = listRunLogs(project, settings.name)
                .filterNot { it.logFile in liveLogs }
                .take(historyLimit),
            warnings = warningsFor(settings, storage, definition),
        )
        return McpToolCallResult.text(summaryOf(inspection), inspection.toJson())
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
        return "${inspection.configurationName} (${inspection.typeDisplayName}): $storage, $runnable, " +
            "${inspection.instances.size} live instance(s), ${inspection.history.size} earlier run(s)."
    }
}
