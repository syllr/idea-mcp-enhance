package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 一个项目级工具对终端环境的贡献。PATH 只放该工具自己的 bin，基础 PATH 由 registry 统一拼。 */
internal data class ProjectTerminalEnv(
    val pathEntries: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
)

internal interface ProjectTerminalEnvProvider {
    fun extraEnv(project: Project): ProjectTerminalEnv
}

internal class ProjectTerminalEnvRegistry {
    private val providers = CopyOnWriteArrayList<ProjectTerminalEnvProvider>()

    fun register(provider: ProjectTerminalEnvProvider) {
        if (!providers.contains(provider)) providers.add(provider)
    }

    fun extraEnv(project: Project): Map<String, String> {
        val paths = LinkedHashSet<String>()
        val variables = LinkedHashMap<String, String>()

        providers.forEach { provider ->
            val env = provider.extraEnv(project)
            paths.addAll(env.pathEntries)
            variables.putAll(env.variables)
        }

        projectJdkBin(project)?.let { paths.add(it) }
        System.getenv("PATH")?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { paths.add(it) }

        if (paths.isNotEmpty()) {
            variables["PATH"] = paths.joinToString(File.pathSeparator)
        }
        return variables
    }

    private fun projectJdkBin(project: Project): String? =
        ProjectRootManager.getInstance(project).projectSdk?.homePath
            ?.let { File(it, "bin").absolutePath }
}
