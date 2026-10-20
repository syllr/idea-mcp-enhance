package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 定义态**写操作**的平台接线：建 / 改 / 删 / 改名，以及引用的查找与清理。
 *
 * ## 通用类型怎么做到"不写十套代码"
 *
 * 用平台自己的序列化器（`DESIGN.md` §7.6）：
 *
 * 1. `ConfigurationTypeUtil.findConfigurationType(typeId)` 找类型
 * 2. 内容来源三选一：`cloneFrom` / 平台模板 / 调用方给的 XML
 * 3. 灌进一个临时配置对象：`RunConfiguration.readExternal(Element)` —— 该类型自己的代码在解析
 * 4. 再 `writeExternal` 一次，把"请求里给了、平台不认识的字段"挑出来（round-trip 对比）
 *
 * 这样任意已安装的类型都能建；平台负责校验字段，我们只负责搬运。
 */

/** 一个配置类型 + 它的第一个 factory。 */
internal data class RunConfigurationTypeInfo(
    val id: String,
    val displayName: String,
    val factory: ConfigurationFactory,
)

/** 找类型；IDE 没装对应插件时返回 null。 */
internal fun findConfigurationTypeInfo(typeId: String): RunConfigurationTypeInfo? {
    val type = ConfigurationTypeUtil.findConfigurationType(typeId) ?: return null
    val factory = type.configurationFactories.firstOrNull() ?: return null
    return RunConfigurationTypeInfo(id = type.id, displayName = type.displayName, factory = factory)
}

/**
 * 造一个**未注册**的配置对象，用来校验 / 序列化。
 *
 * 它不会被加进 RunManager，用完即弃 —— 所以不会在 IDE 里留下任何东西。
 */
internal fun detachedConfiguration(
    project: Project,
    typeInfo: RunConfigurationTypeInfo,
    name: String,
): RunConfiguration = RunManager.getInstance(project).createConfiguration(name, typeInfo.factory).configuration

/**
 * 把元素灌进一个临时配置对象做校验。
 *
 * @return 平台不认识的字段（请求里给了、`writeExternal` 之后消失了）
 */
internal fun validateConfigurationElement(
    project: Project,
    typeInfo: RunConfigurationTypeInfo,
    name: String,
    element: Element,
    requestedOptions: Set<String>,
): Set<String> {
    val configuration = detachedConfiguration(project, typeInfo, name)
    configuration.readExternal(element)
    val normalized = Element("configuration")
    configuration.writeExternal(normalized)
    val known = normalized.getChildren("option").mapNotNull { it.getAttributeValue("name") }.toSet()
    // `<envs>` / `<method>` 不在 writeExternal 的输出里，所以只对比 `<option>` 的字段名。
    return requestedOptions - known
}

/** 平台给这个类型准备的默认配置（模板） → `<configuration>` 元素。 */
internal fun templateConfigurationElement(
    project: Project,
    typeInfo: RunConfigurationTypeInfo,
    name: String,
): Element {
    val template = RunManager.getInstance(project).getConfigurationTemplate(typeInfo.factory)
    val element = Element("configuration")
    template.configuration.writeExternal(element)
    element.setAttribute("name", name)
    element.setAttribute("type", typeInfo.id)
    return element
}

/** 解析调用方给的 XML：接受完整的 `<configuration>`，也接受只给内部字段。 */
internal fun parseConfigurationXml(xml: String): Element? {
    val trimmed = xml.trim()
    if (trimmed.isEmpty()) return null
    val wrapped = if (trimmed.startsWith("<configuration")) trimmed
    else "<configuration>$trimmed</configuration>"
    val document = runCatching { secureSaxBuilder().build(wrapped.byteInputStream()) }.getOrNull() ?: return null
    return document.rootElement.takeIf { it.name == "configuration" }
}

/** 把 `<configuration>` 包成 IDE 认识的整文件形状。 */
internal fun runConfigurationFileContent(element: Element): String =
    "<component name=\"ProjectRunConfigurationManager\">\n" +
        writeConfigurationXml(element) + "\n" +
        "</component>\n"

/**
 * 写配置文件。
 *
 * `overwrite = false` 时目标已存在就**不覆盖**（新建路径）；`true` 时整体替换（更新路径）。
 * 写完必须 `refreshAndFindFileByNioFile`：平台的 `RCInArbitraryFileListener` 只处理
 * `LocalFileSystem` 的 `VFileCreateEvent`，纯 nio 写的文件 VFS 注意不到，就既不触发监听器
 * 也不进配置列表（见 `AGENTS.md` 的实测记录）。
 */
internal fun writeRunConfigurationFile(path: Path, content: String, overwrite: Boolean): Boolean = runCatching {
    path.parent?.let { Files.createDirectories(it) }
    if (overwrite) {
        Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    } else {
        Files.writeString(path, content, StandardOpenOption.CREATE_NEW)
    }
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    true
}.getOrDefault(false)

/**
 * 写配置文件，**优先改编辑器里那份 Document**。
 *
 * 这是实测出来的必须步骤：文件在编辑器里打开时，只写磁盘的话 IDE 手里那份 `Document`
 * 仍是旧内容 —— `read_file` 读到的还是旧值，用户一保存就把我们的改动覆盖回去
 * （正是"外部写完 + reformat 把新内容冲掉"的同一个机制）。
 *
 * 文件没打开时退回普通写盘 + VFS refresh。
 */
internal fun writeRunConfigurationFileSynced(
    project: Project,
    path: Path,
    content: String,
    overwrite: Boolean,
): Boolean {
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
    if (document == null) return writeRunConfigurationFile(path, content, overwrite)
    if (!overwrite) return false
    ApplicationManager.getApplication().invokeAndWait {
        WriteCommandAction.runWriteCommandAction(project) { document.setText(content) }
        FileDocumentManager.getInstance().saveDocument(document)
    }
    return true
}

/** 名字是否可以作为文件名：不能带路径分隔符，不能是空的。 */
internal fun isUsableConfigurationName(name: String): Boolean =
    name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

/** 忽略大小写地找同名配置 —— macOS 的文件系统大小写不敏感，`Console` 与 `console` 会撞同一个文件。 */
internal fun findConfigurationByNameIgnoringCase(project: Project, name: String): String? =
    RunManager.getInstance(project).allSettings
        .firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?.name

// ---------------------------------------------------------------------------
// 引用：别人对我的引用
// ---------------------------------------------------------------------------

/** 一处引用。 */
internal data class RunConfigurationReference(val file: Path, val referencingConfiguration: String)

/**
 * 找出所有对 [name] 的引用。
 *
 * 两类：
 *
 * - **before-launch**：`<method>` 里的 `run_configuration_name="<name>"`（"先跑另一个配置"）
 * - **复合配置**：`<option name="configuration">` 下面的 `<option value="<name>" />`
 */
internal fun findReferencesTo(project: Project, name: String): List<RunConfigurationReference> {
    val directory = projectBase(project)?.resolve(".run") ?: return emptyList()
    if (!Files.isDirectory(directory)) return emptyList()
    val references = mutableListOf<RunConfigurationReference>()
    runCatching {
        Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".run.xml") }.forEach { file ->
                val root = runCatching { secureSaxBuilder().build(file.toFile()) }.getOrNull() ?: return@forEach
                val configuration = root.rootElement.getChild("configuration") ?: return@forEach
                if (configuration.getAttributeValue("name") == name) return@forEach
                if (hasReferenceTo(configuration, name)) {
                    references += RunConfigurationReference(
                        file = file,
                        referencingConfiguration = configuration.getAttributeValue("name") ?: file.fileName.toString(),
                    )
                }
            }
        }
    }
    return references
}

private fun hasReferenceTo(configuration: Element, name: String): Boolean {
    val method = configuration.getChild("method")
    if (method != null && method.children.any { it.getAttributeValue("run_configuration_name") == name }) return true
    val compound = configuration.getChild("option")?.takeIf { it.getAttributeValue("name") == "configuration" }
    return compound?.getChild("list")?.children?.any { it.getAttributeValue("value") == name } ?: false
}

/**
 * 把指向 [name] 的引用删掉（改名时改成新名字，删除时移除）。
 *
 * @param replacement 非 null = 改名，引用改成它；null = 删除，引用直接删掉
 * @return 被改写的文件
 */
internal fun rewriteReferences(
    project: Project,
    name: String,
    replacement: String?,
): List<Path> {
    val directory = projectBase(project)?.resolve(".run") ?: return emptyList()
    if (!Files.isDirectory(directory)) return emptyList()
    val changed = mutableListOf<Path>()
    runCatching {
        Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".run.xml") }.forEach { file ->
                val root = runCatching { secureSaxBuilder().build(file.toFile()) }.getOrNull() ?: return@forEach
                val configuration = root.rootElement.getChild("configuration") ?: return@forEach
                if (configuration.getAttributeValue("name") == name) return@forEach
                var touched = false
                val method = configuration.getChild("method")
                if (method != null) {
                    val matches = method.children.filter { it.getAttributeValue("run_configuration_name") == name }
                    if (matches.isNotEmpty()) {
                        if (replacement == null) matches.forEach { method.removeContent(it) }
                        else matches.forEach { it.setAttribute("run_configuration_name", replacement) }
                        touched = true
                    }
                }
                val compound = configuration.getChild("option")?.takeIf { it.getAttributeValue("name") == "configuration" }
                val list = compound?.getChild("list")
                if (list != null) {
                    val matches = list.children.filter { it.getAttributeValue("value") == name }
                    if (matches.isNotEmpty()) {
                        if (replacement == null) matches.forEach { list.removeContent(it) }
                        else matches.forEach { it.setAttribute("value", replacement) }
                        touched = true
                    }
                }
                if (!touched) return@forEach
                writeRunConfigurationFile(file, runConfigurationFileContent(configuration), overwrite = true)
                changed.add(file)
            }
        }
    }
    return changed
}

/**
 * 把写在盘上的内容**同步回 IDE 内存里的那个配置对象**。
 *
 * 这是实测出来的必需步骤：外部改完 `.run.xml` 之后，`RunManager` 手里那个
 * `RunConfiguration` 实例**不会自动重读**（文件监听只处理创建 / 删除这类事件）。
 * 于是"文件是新的、点 Run 跑的还是旧的"——实测踩到过：更新 `SCRIPT_TEXT` 之后执行，
 * 进程用的仍是旧脚本。
 *
 * `readExternal` 是平台自己的反序列化入口（与加载 `.run.xml` 时用的是同一个），
 * 所以任意类型都能用它把内存对象对齐到最新文件。
 *
 * @return false = 同步失败（类型不支持 / 内容被拒），调用方 MUST 如实报告
 *   "IDE 可能仍在用旧值，重启 IDE 后才会生效"
 */
internal fun syncInMemoryConfiguration(
    settings: RunnerAndConfigurationSettings,
    element: Element,
): Boolean {
    var synced = false
    ApplicationManager.getApplication().invokeAndWait {
        synced = runCatching {
            settings.configuration.readExternal(element)
            true
        }.getOrDefault(false)
    }
    return synced
}

/** 项目根目录。 */
internal fun projectBase(project: Project): Path? = project.basePath?.let(Path::of)

/**
 * 删掉配置文件，并让 VFS 立刻看到删除。
 *
 * 走 VFS 删除（而不是只 `Files.delete`）是为了让平台的 `RCInArbitraryFileListener`
 * 收到删除事件，配置才会从 RunManager 里消失。
 */
internal fun deleteRunConfigurationFile(path: Path): Boolean {
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    if (virtualFile != null) {
        runCatching {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .runWriteAction { virtualFile.delete(null) }
        }
    }
    if (Files.exists(path)) runCatching { Files.deleteIfExists(path) }
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    return !Files.exists(path)
}
