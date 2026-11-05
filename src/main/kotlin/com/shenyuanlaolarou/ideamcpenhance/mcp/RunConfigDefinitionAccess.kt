package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path

/**
 * Run Configuration **定义态**的平台接线：配置存在哪、里面写了什么。
 *
 * ## 为什么读盘上的文件，而不是问平台要 XML
 *
 * `RunConfiguration.writeExternal(Element)` 只序列化**类型自己的字段**：
 * `<option>` 那些它在，但 `<envs>`（环境变量）与 `<method>`（前置任务）**不在** ——
 * 那两样是存储层另外写的。所以平台序列化出来的 XML 拼不回一个完整的 `.run.xml`。
 *
 * 而盘上的文件是完整的：IDE 自己写出来的形状（`<option>` / `<envs>` / `<method>` 全在）。
 * 所以**读**走盘上的文件；只有拿不到文件时才退回到平台序列化（至少有 `<option>`）。
 *
 * 这里**只读**：配置的新建 / 修改 / 删除回到 IDE 界面做。
 */

/** 配置存在哪、是否共享。 */
internal enum class RunConfigurationStorage(val id: String) {
    /** 项目级共享配置：`<project>/.run/<name>.run.xml`。**我们管理的就这一种。** */
    RUN_FILE("runFile"),

    /** IDE 级共享配置：`<project>/.idea/runConfigurations/<name>.xml`。 */
    DOT_IDEA("dotIdea"),

    /** 本机工作区配置：存在 `.idea/workspace.xml` 里，没有独立的文件。 */
    WORKSPACE("workspace"),

    /** 临时配置（还没保存过）。 */
    TEMPORARY("temporary"),

    /** 认不出来。 */
    UNKNOWN("unknown"),
}

/** 一个前置任务（before-launch step）。 */
internal data class RunBeforeLaunchStep(
    /** 任务种类，即 `<option name="…">` 上的 name（`RunConfigurationTask`、`MakeBeforeRunTask` 等）。 */
    val taskKind: String?,
    /** 这个任务指向哪个 run configuration（"跑另一个配置"时才有）。 */
    val configurationName: String?,
    /** 被挂配置自己的类型 id（`run_configuration_type`），例如 `NodeJSConfigurationType`。 */
    val configurationType: String?,
    /** 这一条是否启用。缺省按启用处理（平台写文件时只在需要时写 `enabled`）。 */
    val enabled: Boolean,
)

/** 从 XML 里解析出来的配置定义。 */
internal data class RunConfigurationDefinition(
    /** `<configuration>` 标签上的属性（不含 `name` / `type`）。有些类型把字段写在属性上（如 Node.js）。 */
    val attributes: Map<String, String>,
    /** `<option name= value=>` 组成的表。 */
    val options: Map<String, String>,
    /** `<envs>` 里的环境变量。 */
    val envs: Map<String, String>,
    /** `<method>` 里的前置任务，按执行顺序排列。 */
    val beforeRunTasks: List<RunBeforeLaunchStep>,
)

/** 这个配置存在哪。 */
internal fun runConfigurationStorageOf(settings: RunnerAndConfigurationSettings): RunConfigurationStorage = when {
    settings.pathIfStoredInArbitraryFileInProject != null -> RunConfigurationStorage.RUN_FILE
    settings.isStoredInDotIdeaFolder -> RunConfigurationStorage.DOT_IDEA
    settings.isStoredInLocalWorkspace -> RunConfigurationStorage.WORKSPACE
    settings.isTemporary -> RunConfigurationStorage.TEMPORARY
    else -> RunConfigurationStorage.UNKNOWN
}

/**
 * 这个配置对应的**文件**（如果有）。
 *
 * 项目级的是 `<project>/.run/<name>.run.xml`；IDE 级的是 `<project>/.idea/runConfigurations/<name>.xml`。
 * 工作区级与临时配置没有独立文件（返回 null）。
 */
internal fun runConfigurationFileOf(
    project: Project,
    settings: RunnerAndConfigurationSettings,
): Path? {
    settings.pathIfStoredInArbitraryFileInProject?.let { return Path.of(it) }
    if (settings.isStoredInDotIdeaFolder) {
        val base = project.basePath ?: return null
        return Path.of(base, ".idea", "runConfigurations", "${settings.name}.xml")
    }
    return null
}

/**
 * 读这个配置的定义。
 *
 * 读不到文件时（工作区级 / 临时配置）返回 null —— 调用方据此说"这个配置没有可读的定义"。
 */
internal fun readRunConfigurationDefinition(
    project: Project,
    settings: RunnerAndConfigurationSettings,
): RunConfigurationDefinition? {
    val configuration = readRunConfigurationElement(project, settings) ?: return null
    return parseRunConfigurationDefinition(configuration)
}

/**
 * 读这个配置的 `<configuration>` 元素（原样，未改动）。
 *
 * 读不到文件时返回 null。**优先盘上的文件**是刻意的：它包含 `<envs>` 与 `<method>`，
 * 而平台序列化出来的 XML 没有那两段。
 */
internal fun readRunConfigurationElement(
    project: Project,
    settings: RunnerAndConfigurationSettings,
): Element? {
    val file = runConfigurationFileOf(project, settings) ?: return null
    if (!Files.isRegularFile(file)) return null
    // 用平台的 `JDOMUtil.load` 而不是裸 `SAXBuilder`：后者会把缩进空白保留成 `org.jdom.Text` 节点，
    // 而 Maven / Gradle 那类走 `XmlSerializer` 的反序列化器会直接 `Text as Element` 崩掉
    // （实测：`ClassCastException: class org.jdom.Text cannot be cast to class org.jdom.Element`）。
    // `JDOMUtil` 是平台自己的加载器，与 IDE 读 `.run.xml` 时用的是同一套语义。
    val root = runCatching { com.intellij.openapi.util.JDOMUtil.load(file) }.getOrNull() ?: return null
    return root.getChild("configuration")
}


/** 解析 `<configuration>` 元素。 */
internal fun parseRunConfigurationDefinition(configuration: Element): RunConfigurationDefinition {
    val attributes = LinkedHashMap<String, String>()
    for (attribute in configuration.attributes) {
        val key = attribute.name
        if (key == "name" || key == "type") continue
        attributes[key] = attribute.value
    }

    val options = LinkedHashMap<String, String>()
    for (option in configuration.getChildren("option")) {
        val name = option.getAttributeValue("name") ?: continue
        options[name] = option.getAttributeValue("value") ?: ""
    }

    val envs = LinkedHashMap<String, String>()
    configuration.getChild("envs")?.let { envsElement ->
        for (env in envsElement.getChildren("env")) {
            val name = env.getAttributeValue("name") ?: continue
            envs[name] = env.getAttributeValue("value") ?: ""
        }
    }

    val beforeRunTasks = configuration.getChild("method")?.let { method ->
        method.getChildren("option").map { child ->
            RunBeforeLaunchStep(
                taskKind = child.getAttributeValue("name"),
                configurationName = child.getAttributeValue("run_configuration_name"),
                configurationType = child.getAttributeValue("run_configuration_type"),
                // 平台写文件时只在需要时才写 enabled，缺省即启用。
                enabled = child.getAttributeValue("enabled")?.toBooleanStrictOrNull() ?: true,
            )
        }
    } ?: emptyList()

    return RunConfigurationDefinition(
        attributes = attributes,
        options = options,
        envs = envs,
        beforeRunTasks = beforeRunTasks,
    )
}

/**
 * 把 `<configuration>` 元素拼成 IDE 那样的 XML 文本。
 *
 * 格式与 IDE 自己写的 `.run/` 目录下的 `*.run.xml` 一致：两格缩进、自闭合标签是 `<tag … />`（斜杠前有空格）、
 * 属性值里的换行写成 `&#10;`（XML 属性里的原始换行在解析时会被规整成空格）。
 */
internal fun writeConfigurationXml(configuration: Element, baseDepth: Int = 0): String {
    val builder = StringBuilder()
    appendElement(builder, configuration, baseDepth)
    return builder.toString()
}

private fun appendElement(builder: StringBuilder, element: Element, depth: Int) {
    val indent = "  ".repeat(depth)
    builder.append(indent).append('<').append(element.name)
    for (attribute in element.attributes) {
        builder.append(' ').append(attribute.name).append("=\"").append(escapeXmlAttribute(attribute.value)).append('"')
    }
    val children = element.children
    if (children.isEmpty()) {
        builder.append(" />")
        return
    }
    builder.append(">\n")
    for (child in children) {
        appendElement(builder, child, depth + 1)
        builder.append('\n')
    }
    builder.append(indent).append("</").append(element.name).append('>')
}

/** XML 属性值的转义：& < > "，以及换行（写成 `&#10;`，否则解析时会被规整成空格）。 */
internal fun escapeXmlAttribute(value: String): String {
    val builder = StringBuilder(value.length + 8)
    for (ch in value) {
        when (ch) {
            '&' -> builder.append("&amp;")
            '<' -> builder.append("&lt;")
            '>' -> builder.append("&gt;")
            '"' -> builder.append("&quot;")
            '\n' -> builder.append("&#10;")
            '\r' -> builder.append("&#13;")
            else -> builder.append(ch)
        }
    }
    return builder.toString()
}
