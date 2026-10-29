package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import java.io.File

/**
 * 把项目配置的 JDK 注入环境（只加 `bin` 到 PATH）。
 *
 * 不写 `JAVA_HOME`：Maven / Gradle 自己会从 project SDK 解析，终端里只要 `java` / `javac`
 * 能命中正确的版本就够，多余的变量反而会盖掉用户 shell 里已有的配置。
 *
 * `ProjectRootManager` 是核心 API，所以这个 registrar 不需要 optional 依赖。
 */
class JavaTerminalEnvRegistrar : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ProjectTerminalEnvRegistry>().register(JavaTerminalEnvProvider)
    }
}

private object JavaTerminalEnvProvider : ProjectTerminalEnvProvider {
    override val id = ProjectEnvProvider.Java

    override fun extraEnv(project: Project): ProjectTerminalEnv {
        val home = ProjectRootManager.getInstance(project).projectSdk?.homePath
            ?.takeIf { it.isNotBlank() }
            ?: return ProjectTerminalEnv()
        return ProjectTerminalEnv(pathEntries = listOf(File(home, "bin").absolutePath))
    }
}
