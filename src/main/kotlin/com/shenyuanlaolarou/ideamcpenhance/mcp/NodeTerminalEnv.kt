package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.javascript.nodejs.npm.NpmManager
import com.intellij.javascript.nodejs.util.NodePackage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.io.File

/** 把项目配置的 Node.js runtime 与 package manager 注入本地终端 PATH。 */
class NodeTerminalEnvRegistrar : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ProjectTerminalEnvRegistry>().register(NodeTerminalEnvProvider)
    }
}

private object NodeTerminalEnvProvider : ProjectTerminalEnvProvider {
    override fun extraEnv(project: Project): ProjectTerminalEnv {
        val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
            ?: return ProjectTerminalEnv()
        val local = NodeJsLocalInterpreter.tryCast(interpreter) ?: return ProjectTerminalEnv()
        val nodePath = local.interpreterSystemDependentPath.takeIf { it.isNotBlank() }
            ?: return ProjectTerminalEnv()
        val nodeBin = File(nodePath).parentFile?.absolutePath ?: return ProjectTerminalEnv()

        val packageManager = NpmManager.getInstance(project).getPackage()
        val packageManagerBin = packageManager?.let { pkg -> binDirectoryOf(pkg) }

        return ProjectTerminalEnv(
            pathEntries = listOfNotNull(nodeBin, packageManagerBin).distinct(),
        )
    }

    /** 把 IDE 配置的 package manager 路径换算成应加入 PATH 的目录。 */
    private fun binDirectoryOf(pkg: NodePackage): String? {
        val path = pkg.systemDependentPath
        if (path.isBlank()) return null
        val file = File(path)
        // 配置值通常是可执行文件（pnpm/yarn/npm 的 shim）。
        if (file.isFile) return file.parentFile?.absolutePath
        if (!file.isDirectory) return null
        // 少数情况下指向包含 package.json 的包目录，其 bin 子目录才是入口。
        val bin = File(file, "bin")
        return bin.absolutePath.takeIf { bin.isDirectory }
    }
}
