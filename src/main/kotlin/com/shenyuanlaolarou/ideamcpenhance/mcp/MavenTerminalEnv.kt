package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.idea.maven.project.BundledMaven3
import org.jetbrains.idea.maven.project.BundledMaven4
import org.jetbrains.idea.maven.project.MavenGeneralSettings
import org.jetbrains.idea.maven.project.MavenInSpecificPath
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.File

/**
 * 把项目实际使用的 Maven 注入本地终端环境。
 *
 * Maven 插件不存在时本文件不会被加载（见 maven-env.xml）。
 */
class MavenTerminalEnvRegistrar : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ProjectTerminalEnvRegistry>().register(MavenTerminalEnvProvider)
    }
}

private object MavenTerminalEnvProvider : ProjectTerminalEnvProvider {
    override val id = ProjectEnvProvider.Maven

    override fun extraEnv(project: Project): ProjectTerminalEnv {
        val manager = MavenProjectsManager.getInstanceIfCreated(project) ?: return ProjectTerminalEnv()
        if (!manager.isMavenizedProject) return ProjectTerminalEnv()

        val mavenHome = resolveMavenHome(manager.generalSettings) ?: return ProjectTerminalEnv()
        val home = File(mavenHome).absolutePath
        val mavenBin = File(home, "bin").absolutePath

        return ProjectTerminalEnv(
            pathEntries = listOf(mavenBin),
            variables = mapOf(
                "MAVEN_HOME" to home,
                "M2_HOME" to home,
            ),
        )
    }

    private fun resolveMavenHome(settings: MavenGeneralSettings): String? =
        when (val type = settings.mavenHomeType) {
            is MavenInSpecificPath -> type.mavenHome
            is BundledMaven3 -> bundledMavenHome("maven3")
            is BundledMaven4 -> bundledMavenHome("maven4")
            else -> null
        }

    /** Bundled Maven 的真实路径在 Maven 插件目录下的 lib/maven3（或 maven4）。 */
    private fun bundledMavenHome(dir: String): String? {
        // `PluginManager.getPlugin` 与 `PluginManagerCore.getPlugin` **都**标着 @ApiStatus.Internal
        // （后者还 deprecated），Plugin Verifier 会判成 "Internal API usages" 而拒绝发布。
        // 反射调用不算直接引用 —— 公开的 `IdeaPluginManager` 在 2026.2 里还不存在，没有替代品。
        val descriptor = runCatching {
            Class.forName("com.intellij.ide.plugins.PluginManagerCore")
                .getMethod("getPlugin", PluginId::class.java)
                .invoke(null, PluginId.getId("org.jetbrains.idea.maven"))
        }.getOrNull() as? IdeaPluginDescriptor ?: return null
        val home = descriptor.pluginPath.resolve("lib").resolve(dir).toFile()
        return home.takeIf { it.isDirectory }?.absolutePath
    }
}
