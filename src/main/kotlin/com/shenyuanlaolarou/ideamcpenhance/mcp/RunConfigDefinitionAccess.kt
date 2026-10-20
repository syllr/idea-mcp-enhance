package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import org.jdom.Element
import org.jdom.input.SAXBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Run Configuration **定义态**的平台接线：配置存在哪、里面写了什么、怎么写回去。
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
 * ## 为什么写回时自己拼 XML
 *
 * IDE 写出来的 `.run/` 目录下的 `*.run.xml` 有固定格式：两格缩进、自闭合标签是 `<tag … />`（斜杠前有空格）。
 * 用 JDOM 默认的输出器会写成 `<tag/>`、且不缩进 —— 与 IDE 自己写的文件长得不一样，
 * 于是每次改动都产生一堆 diff 噪音。所以这里自己拼，格式与 IDE 一致。
 */

/** 配置存在哪。决定我们能不能改它。 */
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
    /** 任务类型，比如 `RunConfigurationRunTask`、`MakeBeforeRunTask`。认不出来时为 null。 */
    val type: String?,
    /** 这个任务指向哪个 run configuration（"跑另一个配置"时才有）。 */
    val referencedConfiguration: String?,
)

/** 从 XML 里解析出来的配置定义。 */
internal data class RunConfigurationDefinition(
    /** `<configuration>` 标签上的属性（不含 `name` / `type`）。有些类型把字段写在属性上（如 Node.js）。 */
    val attributes: Map<String, String>,
    /** `<option name= value=>` 组成的表。 */
    val options: Map<String, String>,
    /** `<envs>` 里的环境变量。 */
    val envs: Map<String, String>,
    /** `<method>` 里的前置任务。 */
    val beforeLaunchSteps: List<RunBeforeLaunchStep>,
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
    val document = runCatching { secureSaxBuilder().build(file.toFile()) }.getOrNull() ?: return null
    return document.rootElement.getChild("configuration")
}

/**
 * 关掉 DOCTYPE 与外部实体的 SAX 解析器。
 *
 * 解析的 XML 来自两处：项目里的 `.run.xml`，以及**调用方传进来的** `configurationXml`（agent 生成的）。
 * 后者是模型输出，不该有机会触发外部实体解析（XXE）。特性是否支持因解析器而异，所以逐条 `runCatching`。
 */
internal fun secureSaxBuilder(): SAXBuilder = SAXBuilder().apply {
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
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

    val beforeLaunch = configuration.getChild("method")?.let { method ->
        method.children.map { child ->
            RunBeforeLaunchStep(
                type = child.getAttributeValue("name"),
                referencedConfiguration = child.getAttributeValue("run_configuration_name"),
            )
        }
    } ?: emptyList()

    return RunConfigurationDefinition(
        attributes = attributes,
        options = options,
        envs = envs,
        beforeLaunchSteps = beforeLaunch,
    )
}

/**
 * 把 `<configuration>` 元素拼成 IDE 那样的 XML 文本。
 *
 * 格式与 IDE 自己写的 `.run/` 目录下的 `*.run.xml` 一致：两格缩进、自闭合标签是 `<tag … />`（斜杠前有空格）、
 * 属性值里的换行写成 `&#10;`（XML 属性里的原始换行在解析时会被规整成空格）。
 */
internal fun writeConfigurationXml(configuration: Element): String {
    val builder = StringBuilder()
    appendElement(builder, configuration, 0)
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
