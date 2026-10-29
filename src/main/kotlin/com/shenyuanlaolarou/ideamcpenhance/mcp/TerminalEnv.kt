package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 项目级工具环境：把 IDE 里配置的 SDK / runtime 变成一组环境变量，交给 OpenCode 的
 * `shell.hook("create.before")` 注入到它**自己的** shell 里。
 *
 * 这里只负责**采集**，不负责拼接调用方的 PATH ——  `get_project_env` 返回的 `PATH`
 * 只包含这五个 provider 的 bin，由调用方前置拼到它原有的 PATH 前面。
 */

/**
 * 五个 provider，**声明顺序就是 PATH 里的顺序**（Java → Node → Go → Maven → Python）。
 *
 * 顺序必须稳定：provider 是按 `ProjectActivity` 的启动顺序注册的，直接遍历注册表
 * 会让 PATH 每次启动都可能不同。
 */
internal enum class ProjectEnvProvider {
    Java,
    Node,
    Go,
    Maven,
    Python,
}

/** 一个 provider 对终端环境的贡献：要加进 PATH 的目录 + 它自己的标量变量。 */
internal data class ProjectTerminalEnv(
    val pathEntries: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
)

internal interface ProjectTerminalEnvProvider {
    val id: ProjectEnvProvider
    fun extraEnv(project: Project): ProjectTerminalEnv
}

/**
 * 聚合结果。
 *
 * 形状固定为两层：`PATH` 是拼好的字符串（`File.pathSeparator`），`variables` 是各
 * provider 的标量变量。调用方只需：
 *
 * ```
 * PATH = env.PATH + ":" + existingPath
 * variables.forEach { e.env[it.key] = it.value }
 * ```
 */
internal data class ProjectEnv(
    val path: String,
    val variables: Map<String, String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("PATH", JsonPrimitive(path))
        put(
            "variables",
            buildJsonObject {
                variables.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            },
        )
    }
}

internal class ProjectTerminalEnvRegistry {

    private val providers = ConcurrentHashMap<ProjectEnvProvider, ProjectTerminalEnvProvider>()

    fun register(provider: ProjectTerminalEnvProvider) {
        providers[provider.id] = provider
    }

    /**
     * 按 [ProjectEnvProvider] 的声明顺序聚合。
     *
     * 只收集 provider 自己的条目：不掺 IDE 进程的 `PATH`（那会把调用方的 PATH 顶掉），
     * 也不单独拼项目 JDK —— 它本身就是一个 provider。
     */
    fun aggregate(project: Project): ProjectEnv {
        val paths = LinkedHashSet<String>()
        val variables = LinkedHashMap<String, String>()

        ProjectEnvProvider.entries.forEach { id ->
            val env = providers[id]?.extraEnv(project) ?: return@forEach
            paths.addAll(env.pathEntries.filter { it.isNotBlank() })
            variables.putAll(env.variables)
        }

        return ProjectEnv(
            path = paths.joinToString(File.pathSeparator),
            variables = variables,
        )
    }
}
