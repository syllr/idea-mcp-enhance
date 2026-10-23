package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.jetbrains.python.sdk.PythonSdkUtil
import java.io.File

/** 把项目配置的 Python 解释器（通常是 venv）注入本地终端。 */
class PythonTerminalEnvRegistrar : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ProjectTerminalEnvRegistry>().register(PythonTerminalEnvProvider)
    }
}

private object PythonTerminalEnvProvider : ProjectTerminalEnvProvider {
    override fun extraEnv(project: Project): ProjectTerminalEnv {
        val sdk = findPythonSdk(project)
        val interpreter = sdk?.homePath?.takeIf { it.isNotBlank() }
            ?: findProjectVenvInterpreter(project)
            ?: return ProjectTerminalEnv()
        val bin = File(interpreter).parentFile?.absolutePath ?: return ProjectTerminalEnv()
        val venvRoot = venvRootOf(interpreter)

        return ProjectTerminalEnv(
            pathEntries = listOf(bin),
            variables = venvRoot?.let { mapOf("VIRTUAL_ENV" to it) } ?: emptyMap(),
        )
    }

    /** 优先用 IDE 分配给项目/模块的 Python SDK，其次用项目内的 venv 兜底。 */
    private fun findPythonSdk(project: Project): Sdk? {
        ProjectRootManager.getInstance(project).projectSdk
            ?.takeIf { PythonSdkUtil.isPythonSdk(it) && !PythonSdkUtil.isRemote(it) }
            ?.let { return it }

        ModuleManager.getInstance(project).modules.forEach { module ->
            PythonSdkUtil.findPythonSdk(module)
                ?.takeIf { !PythonSdkUtil.isRemote(it) }
                ?.let { return it }
        }

        // 项目模型是 JAVA_MODULE、IDE 没把 Python SDK 分配给模块时，
        // 用注册在项目目录下的 Python SDK 兜底。
        project.basePath?.let { basePath ->
            val prefix = basePath.trimEnd(File.separatorChar) + File.separator
            PythonSdkUtil.getAllSdks()
                .firstOrNull { !PythonSdkUtil.isRemote(it) && it.homePath?.startsWith(prefix) == true }
                ?.let { return it }
        }
        return null
    }

    /**
     * 返回 venv 根目录。只有存在 pyvenv.cfg 才算 venv，避免把系统解释器的
     * 上级目录误写成 VIRTUAL_ENV。
     */
    private fun venvRootOf(interpreter: String): String? {
        val bin = File(interpreter).parentFile ?: return null
        val root = bin.parentFile ?: return null
        if (!File(root, "pyvenv.cfg").isFile) return null
        return root.absolutePath
    }

    /** IDE 还没分配 SDK 时，按项目里常见的 venv 目录兜底。 */
    private fun findProjectVenvInterpreter(project: Project): String? {
        val basePath = project.basePath ?: return null
        return listOf(
            ".venv/bin/python",
            ".venv/Scripts/python.exe",
            "venv/bin/python",
            "venv/Scripts/python.exe",
        ).asSequence()
            .map { File(basePath, it) }
            .firstOrNull { it.isFile }
            ?.absolutePath
    }
}
