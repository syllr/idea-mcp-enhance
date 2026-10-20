package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolsProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 定义态工具：看 / 建 / 改 / 删 Run Configuration。
 *
 * 与运行态（`list` / `stop` / `rerun`）分开的理由：那一半回答"现在什么在跑"，
 * 这一半回答"这个配置是什么、怎么改、日志在哪"。工具分开，agent 少一次选择。
 *
 * ## `.run/` 目录下 `*.run.xml` 的写入口只有这里
 *
 * `create` / `update` / `delete` 是**唯一**的写入口。这条规矩写在工具描述与
 * opencode-idea 侧的 guidance 里（提示词层），**不做**技术拦截 ——
 * 用户明确要求手改某个文件时，工具报错比放行更糟（见 `DESIGN.md` §7.1）。
 */
class RunConfigDefinitionToolsProvider : McpToolsProvider {

    override fun getTools(): List<McpTool> = listOf(
        InspectRunConfigurationTool(),
        CreateRunConfigurationDefinitionTool(),
        UpdateRunConfigurationTool(),
        DeleteRunConfigurationTool(),
    )
}

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
        val inspection = RunConfigurationInspection(
            configurationName = settings.name,
            typeId = settings.type.id,
            typeDisplayName = settings.type.displayName,
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

// ---------------------------------------------------------------------------
// create_run_configuration
// ---------------------------------------------------------------------------

private class CreateRunConfigurationDefinitionTool : BaseRunTool() {

    override val descriptor: McpToolDescriptor = createRunConfigurationDefinitionDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return noProjectResult(CREATE_DEFINITION_TOOL_NAME)
        val name = arguments.requiredString("configurationName") ?: return missingNameError()
        if (!isUsableConfigurationName(name)) {
            return errorResult(
                "\"$name\" cannot be used as a configuration name: it must not be empty or contain `/`, `\\`.",
                null,
            )
        }
        findConfigurationByNameIgnoringCase(project, name)?.let { existing ->
            val suggestion = RunManager.getInstance(project).suggestUniqueName(name, null)
            return errorResult(
                "A run configuration named \"$existing\" already exists (names are matched ignoring case). " +
                    "Nothing was created — pick a free name such as \"$suggestion\".",
                null,
            )
        }
        val storagePath = runConfigurationFilePath(project, name)?.let(Path::of)
            ?: return errorResult("Could not resolve the project root, so there is nowhere to store the configuration.", null)
        if (Files.exists(storagePath)) {
            return errorResult(
                "A file already exists at $storagePath, so a configuration named \"$name\" is already there. " +
                    "Nothing was created or overwritten.",
                null,
            )
        }

        val cloneFrom = arguments.optionalString("cloneFrom")
        val providedXml = arguments.optionalString("configurationXml")
        val requestedType = arguments.optionalString("type")

        val source = cloneFrom?.let { sourceName ->
            val settings = findSettingsByName(project, sourceName)
                ?: return unknownConfiguration(project, sourceName)
            if (settings.isTemplate) {
                return errorResult(
                    "\"${settings.name}\" is a template, not a real configuration, so it cannot be cloned.",
                    null,
                )
            }
            settings
        }

        val typeId = requestedType ?: source?.type?.id
            ?: return errorResult(
                "Missing `type`: give the configuration type id (e.g. `ShConfigurationType`), " +
                    "or `cloneFrom` an existing configuration so the type can be inferred.",
                null,
            )
        if (source != null && source.type.id != typeId) {
            return errorResult(
                "\"${source.name}\" is a ${source.type.displayName} configuration, not `$typeId`. " +
                    "Nothing was created.",
                null,
            )
        }
        val typeInfo = findConfigurationTypeInfo(typeId)
            ?: return errorResult(
                "Configuration type `$typeId` is not available in this IDE — the plugin that provides it is " +
                    "not installed or not enabled. Nothing was created.",
                null,
            )

        val element = when {
            source != null -> {
                val sourceElement = readRunConfigurationElement(project, source)
                    ?: return errorResult(
                        "\"${source.name}\" has no readable `.run.xml` file, so there is nothing to copy. " +
                            "Nothing was created.",
                        null,
                    )
                // 通过"序列化 → 重新解析"来复制，避免依赖 JDOM 的 clone 语义。
                parseConfigurationXml(writeConfigurationXml(sourceElement))
                    ?: return errorResult("Could not copy the body of \"${source.name}\". Nothing was created.", null)
            }
            providedXml != null -> parseConfigurationXml(providedXml)
                ?: return errorResult("`configurationXml` is not valid XML. Nothing was created.", null)
            else -> templateConfigurationElement(project, typeInfo, name)
        }

        element.setAttribute("name", name)
        element.setAttribute("type", typeInfo.id)
        if (element.getAttribute("factoryName") == null) {
            element.setAttribute("factoryName", typeInfo.factory.name)
        }

        // 平台模板是从**当前这台机器**的默认值生成的：实测 Shell 模板带上了
        // `SCRIPT_WORKING_DIRECTORY=/Users/…/<project>`（绝对路径）和 `INTERPRETER_PATH=/bin/zsh`。
        // 这两样都不能进要提交到 git 的配置（违反"项目级可共享"）。
        // 注意顺序：先归一化模板默认值，再套调用方的补丁 —— 调用方显式给的值必须赢。
        if (typeInfo.id == SHELL_CONFIGURATION_TYPE_ID && source == null && providedXml == null) {
            normalizeShellTemplateDefaults(element, project)
        }

        val optionSet = arguments.stringMap("options")
        val removals = arguments.stringMapWithRemovals("options").second + arguments.stringList("removeOptions").toSet()
        val attributeSet = arguments.stringMap("attributes")
        val envSet = arguments.stringMap("envs")
        val envRemovals = arguments.stringMapWithRemovals("envs").second
        applyOptionPatch(element, optionSet, removals)
        applyAttributePatch(element, attributeSet, arguments.stringList("removeAttributes").toSet())
        applyEnvPatch(element, envSet, envRemovals + arguments.stringList("removeEnvs").toSet())

        // 补丁之后再收一次尾：模板的 `EXECUTE_IN_TERMINAL=true` 会让配置在终端里跑
        // （没有 ProcessHandler → 没日志、停不掉、list 看不见）。只有调用方**没显式给**时才改。
        if (typeInfo.id == SHELL_CONFIGURATION_TYPE_ID) {
            val explicit = optionSet.keys
            if ("EXECUTE_IN_TERMINAL" !in explicit) element.setOption("EXECUTE_IN_TERMINAL", "false")
            val scriptText = element.optionValue("SCRIPT_TEXT")
            val scriptPath = element.optionValue("SCRIPT_PATH")
            if ("EXECUTE_SCRIPT_FILE" !in explicit && !scriptText.isNullOrBlank() && scriptPath.isNullOrBlank()) {
                element.setOption("EXECUTE_SCRIPT_FILE", "false")
            }
        }

        val unknown = runCatching {
            validateConfigurationElement(project, typeInfo, name, element, optionSet.keys)
        }.getOrElse { error ->
            return errorResult(
                "The ${typeInfo.displayName} configuration rejected this body: " +
                    (error.message ?: error::class.java.simpleName) + ". Nothing was created.",
                null,
            )
        }

        // 平台不认识的字段不写进文件：它们本来就会被忽略，留下只会让下一个读文件的人困惑。
        if (unknown.isNotEmpty()) unknown.forEach { name -> element.removeOption(name) }

        val content = runConfigurationFileContent(element)
        if (!writeRunConfigurationFile(storagePath, content, overwrite = false)) {
            return errorResult("Could not write $storagePath, so nothing was created.", null)
        }

        val registered = awaitConfigurationRegistered(project, name)
        val blocked = registered?.let { cannotRunReason(it, null) }
        val warnings = buildList {
            if (registered == null) {
                add("The file was written but the IDE has not loaded it yet — check again shortly with `inspect_run_configuration`.")
            }
            if (unknown.isNotEmpty()) {
                add(
                    "These option names were not kept by ${typeInfo.displayName} and are probably misspelled: " +
                        unknown.joinToString(", "),
                )
            }
        }
        val createdFrom = when {
            source != null -> "clone:${source.name}"
            providedXml != null -> "xml"
            else -> "template:${typeInfo.id}"
        }
        val report = buildJsonObject {
            put("configurationName", name)
            put("type", typeInfo.id)
            put("typeDisplayName", typeInfo.displayName)
            put("createdFrom", createdFrom)
            put("storagePath", storagePath.toString())
            put("canRun", blocked == null && registered != null)
            put("canRunBlockedReason", blocked ?: if (registered == null) "The IDE has not loaded it yet." else null)
            putJsonArray("unknownOptions") { unknown.sorted().forEach { add(it) } }
            putJsonArray("warnings") { warnings.forEach { add(it) } }
        }
        return McpToolCallResult.text(
            "Created ${typeInfo.displayName} configuration \"$name\" at $storagePath. " +
                when {
                    registered == null -> "The IDE has not loaded it yet — check again shortly."
                    blocked == null -> "It can run right now."
                    else -> "It cannot run yet: $blocked"
                },
            report,
        )
    }
}

// ---------------------------------------------------------------------------
// update_run_configuration
// ---------------------------------------------------------------------------

private class UpdateRunConfigurationTool : BaseRunTool() {

    override val descriptor: McpToolDescriptor = updateRunConfigurationDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return noProjectResult(UPDATE_TOOL_NAME)
        val name = arguments.requiredString("configurationName") ?: return missingNameError()
        val settings = findSettingsByName(project, name) ?: return unknownConfiguration(project, name)

        if (settings.isTemplate) {
            return errorResult("\"${settings.name}\" is a template, not a configuration: it cannot be changed.", null)
        }
        val storage = runConfigurationStorageOf(settings)
        if (storage != RunConfigurationStorage.RUN_FILE) {
            return errorResult(
                "\"${settings.name}\" is not stored in `.run/` (${storage.id}), so it is not managed by these " +
                    "tools. Edit it in the IDE instead. Nothing was changed.",
                null,
            )
        }
        val path = runConfigurationFileOf(project, settings)
            ?: return errorResult("Could not resolve the file of \"${settings.name}\". Nothing was changed.", null)
        val element = readRunConfigurationElement(project, settings)
            ?: return errorResult("Could not read $path. Nothing was changed.", null)

        val currentXml = writeConfigurationXml(element)
        arguments.optionalString("expectedContentHash")?.let { expected ->
            val actual = sha256Hex(currentXml)
            if (!expected.equals(actual, ignoreCase = true)) {
                return errorResult(
                    "\"${settings.name}\" changed since it was read (expected hash $expected, found $actual). " +
                        "Nothing was changed — inspect it again and retry.",
                    null,
                )
            }
        }

        val newName = arguments.optionalString("newName")
        val renaming = newName != null && newName != settings.name
        if (renaming) {
            if (!isUsableConfigurationName(newName!!)) {
                return errorResult("\"$newName\" cannot be used as a configuration name. Nothing was changed.", null)
            }
            findConfigurationByNameIgnoringCase(project, newName)?.let { existing ->
                val suggestion = RunManager.getInstance(project).suggestUniqueName(newName, null)
                return errorResult(
                    "A run configuration named \"$existing\" already exists. Nothing was changed — pick a free " +
                        "name such as \"$suggestion\".",
                    null,
                )
            }
            if (snapshotOf(project, settings).running) {
                return errorResult(
                    "\"${settings.name}\" is running. Renaming it would leave the live run contents named after " +
                        "the old configuration. Stop it with `stop_run_configuration` first; nothing was changed.",
                    null,
                )
            }
        }

        val replacementXml = arguments.optionalString("replaceConfigurationXml")
        val target = if (replacementXml != null) {
            parseConfigurationXml(replacementXml)
                ?: return errorResult("`replaceConfigurationXml` is not valid XML. Nothing was changed.", null)
        } else {
            element
        }
        val effectiveName = if (renaming) newName!! else settings.name
        target.setAttribute("name", effectiveName)
        target.setAttribute("type", settings.type.id)

        val optionValues = arguments.stringMap("options")
        val optionRemovals = arguments.stringMapWithRemovals("options").second +
            arguments.stringList("removeOptions").toSet()
        val envValues = arguments.stringMap("envs")
        val envRemovals = arguments.stringMapWithRemovals("envs").second + arguments.stringList("removeEnvs").toSet()
        applyOptionPatch(target, optionValues, optionRemovals)
        applyAttributePatch(
            target,
            arguments.stringMap("attributes"),
            arguments.stringList("removeAttributes").toSet(),
        )
        applyEnvPatch(target, envValues, envRemovals)

        val typeInfo = findConfigurationTypeInfo(settings.type.id)
            ?: return errorResult(
                "Configuration type `${settings.type.id}` is not available in this IDE, so the change cannot be " +
                    "validated. Nothing was changed.",
                null,
            )
        val unknown = runCatching {
            validateConfigurationElement(project, typeInfo, effectiveName, target, optionValues.keys)
        }.getOrElse { error ->
            return errorResult(
                "The ${typeInfo.displayName} configuration rejected the change: " +
                    (error.message ?: error::class.java.simpleName) + ". Nothing was changed.",
                null,
            )
        }

        val content = runConfigurationFileContent(target)
        val targetPath = if (renaming) {
            runConfigurationFilePath(project, effectiveName)?.let(Path::of)
                ?: return errorResult("Could not resolve the path for \"$effectiveName\". Nothing was changed.", null)
        } else {
            path
        }

        var referencesUpdated: List<String> = emptyList()
        var logFilesMoved = 0
        var inMemorySyncFailed = false
        if (renaming) {
            // 先写新文件、再删旧文件：中途失败也不会把配置弄丢。
            if (!writeRunConfigurationFile(targetPath, content, overwrite = false)) {
                return errorResult(
                    "Could not write $targetPath, so nothing was renamed. The old configuration is unchanged.",
                    null,
                )
            }
            referencesUpdated = rewriteReferences(project, settings.name, effectiveName)
                .map { it.fileName.toString() }
            logFilesMoved = renameRunLogs(project, settings.name, effectiveName)
            // 先把旧对象从 RunManager 里摘掉：文件删掉的监听事件不保证立刻生效，
            // 留着它就可能出现"新名字在列表里、旧名字也在"的双份状态。
            RunManager.getInstance(project).removeConfiguration(settings)
            deleteRunConfigurationFile(path)
            awaitConfigurationGone(project, settings.name)
        } else {
            // 文件可能正在编辑器里打开：必须改 Document，否则 IDE 那份旧副本会在保存时反冲掉新内容。
            if (!writeRunConfigurationFileSynced(project, path, content, overwrite = true)) {
                return errorResult("Could not write $path. Nothing was changed.", null)
            }
            // 文件是新的还不够：RunManager 手里的对象不会自己重读（实测：不 sync 的话点 Run 跑的还是旧脚本）。
            if (!syncInMemoryConfiguration(settings, target)) {
                inMemorySyncFailed = true
            }
        }

        val registered = awaitConfigurationRegistered(project, effectiveName)
        val blocked = registered?.let { cannotRunReason(it, null) }
        val changed = buildJsonArray {
            if (renaming) add("name")
            optionValues.keys.sorted().forEach { add("option:$it") }
            optionRemovals.sorted().forEach { add("option-removed:$it") }
            envValues.keys.sorted().forEach { add("env:$it") }
            envRemovals.sorted().forEach { add("env-removed:$it") }
            if (replacementXml != null) add("configurationXml")
        }
        val report = buildJsonObject {
            put("configurationName", effectiveName)
            put("previousName", if (renaming) settings.name else null)
            put("renamed", renaming)
            put("storagePath", targetPath.toString())
            put("changed", changed)
            putJsonArray("referencesUpdated") { referencesUpdated.forEach { add(it) } }
            put("logFilesMoved", logFilesMoved)
            put("canRun", blocked == null && registered != null)
            put("canRunBlockedReason", blocked ?: if (registered == null) "The IDE has not loaded it yet." else null)
            putJsonArray("unknownOptions") { unknown.sorted().forEach { add(it) } }
            putJsonArray("warnings") {
                if (unknown.isNotEmpty()) {
                    add(
                        "These option names were not kept by ${typeInfo.displayName} and are probably misspelled: " +
                            unknown.joinToString(", "),
                    )
                }
                if (registered == null) add("The IDE has not loaded it yet — inspect it again shortly.")
                if (inMemorySyncFailed) {
                    add(
                        "The file was written but the IDE could not update its in-memory copy, so a restart may " +
                            "still run the old settings. Restart the IDE (or reopen the configuration) before starting it.",
                    )
                }
            }
        }
        return McpToolCallResult.text(
            if (renaming) {
                "Renamed \"${settings.name}\" to \"$effectiveName\"; updated ${referencesUpdated.size} reference(s) " +
                    "and moved $logFilesMoved recorded log(s)."
            } else {
                "Updated \"$effectiveName\" at $targetPath."
            },
            report,
        )
    }
}

// ---------------------------------------------------------------------------
// delete_run_configuration
// ---------------------------------------------------------------------------

private class DeleteRunConfigurationTool : BaseRunTool() {

    override val descriptor: McpToolDescriptor = deleteRunConfigurationDescriptor

    override suspend fun body(arguments: JsonObject): McpToolCallResult {
        val project = currentProject() ?: return noProjectResult(DELETE_TOOL_NAME)
        val name = arguments.requiredString("configurationName") ?: return missingNameError()
        val settings = findSettingsByName(project, name) ?: return unknownConfiguration(project, name)

        if (settings.isTemplate) {
            return errorResult("\"${settings.name}\" is a template, not a configuration: it cannot be deleted.", null)
        }
        val storage = runConfigurationStorageOf(settings)
        if (storage != RunConfigurationStorage.RUN_FILE) {
            return errorResult(
                "\"${settings.name}\" is not stored in `.run/` (${storage.id}), so it is not managed by these " +
                    "tools. Delete it in the IDE instead. Nothing was deleted.",
                null,
            )
        }
        val path = runConfigurationFileOf(project, settings)
            ?: return errorResult("Could not resolve the file of \"${settings.name}\". Nothing was deleted.", null)

        if (snapshotOf(project, settings).running) {
            return errorResult(
                "\"${settings.name}\" is running. Stop it with `stop_run_configuration` first; nothing was deleted.",
                null,
            )
        }

        val references = findReferencesTo(project, settings.name)
        val force = arguments.optionalBoolean("force", false)
        if (references.isNotEmpty() && !force) {
            return errorResult(
                "\"${settings.name}\" is referenced by ${references.size} other configuration(s): " +
                    references.joinToString(", ") { it.referencingConfiguration } +
                    ". Nothing was deleted — pass `force = true` to delete it and remove those references.",
                null,
            )
        }

        val removedReferences = mutableListOf<String>()
        if (references.isNotEmpty()) {
            removedReferences += references.map { it.referencingConfiguration }
            rewriteReferences(project, settings.name, null)
        }

        RunManager.getInstance(project).removeConfiguration(settings)
        deleteRunConfigurationFile(path)
        awaitConfigurationGone(project, settings.name)

        val report = buildJsonObject {
            put("configurationName", settings.name)
            put("storagePath", path.toString())
            putJsonArray("removedReferences") { removedReferences.forEach { add(it) } }
            putJsonArray("warnings") {
                if (Files.exists(path)) {
                    add("The configuration was removed from the IDE, but the file is still on disk: $path")
                }
            }
        }
        return McpToolCallResult.text(
            "Deleted \"${settings.name}\"" +
                if (removedReferences.isEmpty()) "." else
                    " and removed ${removedReferences.size} reference(s) to it.",
            report,
        )
    }
}

// ---------------------------------------------------------------------------
// 补丁与解析的小工具
// ---------------------------------------------------------------------------

/** Shell 模板的机器级默认值归一化。只在"从平台模板新建"时用，克隆 / 显式 XML 一律不动。 */
private fun normalizeShellTemplateDefaults(element: Element, project: com.intellij.openapi.project.Project) {
    fun option(name: String): Element? =
        element.getChildren("option").firstOrNull { it.getAttributeValue("name") == name }

    fun set(name: String, value: String) {
        val existing = option(name)
        if (existing != null) existing.setAttribute("value", value)
        else element.addContent(Element("option").setAttribute("name", name).setAttribute("value", value))
    }

    // 工作目录 = 本机项目绝对路径 → 换成 `$PROJECT_DIR$`（仓库里可共享的写法）。
    // **不能留空**：实测 Shell 配置的工作目录留空会继承 IDE 进程的 cwd（IDE 安装目录），
    // 不是项目根 —— `pwd` 实测输出 `…/idea-2026.2.3-aarch64`。
    val projectPath = project.basePath
    val workDir = option("SCRIPT_WORKING_DIRECTORY")?.getAttributeValue("value")
    if (projectPath != null && workDir == projectPath) set("SCRIPT_WORKING_DIRECTORY", "\$PROJECT_DIR\$")

    // 解释器是用户 / IDE 级配置，新建时不要写死本机路径（见 opencode-idea 侧 shell.md 的约定）。
    val interpreter = option("INTERPRETER_PATH")?.getAttributeValue("value")
    if (!interpreter.isNullOrBlank()) set("INTERPRETER_PATH", "")
}

/** 读一个 option 的值。 */
private fun Element.optionValue(name: String): String? =
    getChildren("option").firstOrNull { it.getAttributeValue("name") == name }?.getAttributeValue("value")

/** 设置一个 option 的值（不存在就追加）。 */
private fun Element.setOption(name: String, value: String) {
    val existing = getChildren("option").firstOrNull { it.getAttributeValue("name") == name }
    if (existing != null) existing.setAttribute("value", value)
    else addContent(Element("option").setAttribute("name", name).setAttribute("value", value))
}

/** 删掉一个 option。 */
private fun Element.removeOption(name: String) {
    getChildren("option").toList()
        .filter { it.getAttributeValue("name") == name }
        .forEach { removeContent(it) }
}

/** 设置 / 覆盖 `<option>`；`remove` 里的名字直接删掉。 */
private fun applyOptionPatch(element: Element, values: Map<String, String>, remove: Set<String>) {
    for (key in remove) {
        element.getChildren("option").toList()
            .filter { it.getAttributeValue("name") == key }
            .forEach { element.removeContent(it) }
    }
    for ((key, value) in values) {
        val existing = element.getChildren("option").firstOrNull { it.getAttributeValue("name") == key }
        if (existing != null) {
            existing.setAttribute("value", value)
        } else {
            element.addContent(
                Element("option").setAttribute("name", key).setAttribute("value", value),
            )
        }
    }
}

/** 设置 / 删除 `<configuration>` 标签上的属性。 */
private fun applyAttributePatch(element: Element, values: Map<String, String>, remove: Set<String>) {
    for (key in remove) element.removeAttribute(key)
    for ((key, value) in values) element.setAttribute(key, value)
}

/** 设置 / 删除 `<envs>` 里的环境变量。`<envs>` 不存在时按 IDE 的位置插在 `<method>` 之前。 */
private fun applyEnvPatch(element: Element, values: Map<String, String>, remove: Set<String>) {
    if (values.isEmpty() && remove.isEmpty()) return
    var envs = element.getChild("envs")
    if (envs == null) {
        envs = Element("envs")
        val method = element.getChild("method")
        if (method != null) {
            element.addContent(element.indexOf(method), envs)
        } else {
            element.addContent(envs)
        }
    }
    for (key in remove) {
        envs.getChildren("env").toList()
            .filter { it.getAttributeValue("name") == key }
            .forEach { envs.removeContent(it) }
    }
    for ((key, value) in values) {
        val existing = envs.getChildren("env").firstOrNull { it.getAttributeValue("name") == key }
        if (existing != null) {
            existing.setAttribute("value", value)
        } else {
            envs.addContent(Element("env").setAttribute("name", key).setAttribute("value", value))
        }
    }
}

/**
 * 读一个 `object` 参数，同时区分"设值"与"删除"。
 *
 * `{"A": "1", "B": null}` → 设 A=1、删 B。非字符串的标量（数字 / 布尔）按字面量接受，
 * 与 `RunLifecycleSchemas.stringMap` 的口径一致。
 */
private fun JsonObject.stringMapWithRemovals(key: String): Pair<Map<String, String>, Set<String>> {
    val obj = (this[key] as? JsonObject) ?: return emptyMap<String, String>() to emptySet()
    val values = LinkedHashMap<String, String>()
    val removals = LinkedHashSet<String>()
    for ((name, value) in obj) {
        when {
            value is JsonNull -> removals += name
            value is JsonPrimitive -> values[name] = value.content
        }
    }
    return values to removals
}

/** 读一个字符串数组参数。 */
private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?: emptyList()

/** SHA-256 十六进制小写。 */
private fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
