package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.goide.GoEnvironmentUtil
import com.goide.sdk.GoSdkService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import java.io.File

/** 把项目配置的 Go SDK 注入本地终端 PATH/GOROOT/GOPATH/GOBIN。 */
class GoTerminalEnvRegistrar : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ProjectTerminalEnvRegistry>().register(GoTerminalEnvProvider)
    }
}

private object GoTerminalEnvProvider : ProjectTerminalEnvProvider {
    override fun extraEnv(project: Project): ProjectTerminalEnv {
        val service = GoSdkService.getInstance(project)
        val module = ModuleManager.getInstance(project).modules.firstOrNull()
        val sdk = module?.let { service.getSdk(it) } ?: service.getSdk(null)
        val home = sdk.homePath.takeIf { it.isNotBlank() } ?: return ProjectTerminalEnv()

        val configuredGoPath = GoEnvironmentUtil.retrieveGoPathFromEnvironment()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // IDE/环境变量没有 GOPATH 时，用 Go SDK 自己的有效配置兜底。
        val goEnv = if (configuredGoPath == null) readGoEnv(home) else emptyMap()
        val goPath = configuredGoPath ?: goEnv["GOPATH"]

        val rawGoBin = GoEnvironmentUtil.retrieveGoBinFromEnvironment()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // 没有显式 GOBIN 时该 API 会回退成 GOPATH，不能当成 GOBIN 用。
        val explicitGoBin = rawGoBin?.takeIf { it != goPath }
            ?: goEnv["GOBIN"]?.takeIf { it != goPath }
        val defaultGoBin = goPath
            ?.split(File.pathSeparator)
            ?.firstOrNull { it.isNotBlank() }
            ?.let { File(it, "bin").absolutePath }
        val goBin = explicitGoBin ?: defaultGoBin

        val pathEntries = buildList {
            add(File(home, "bin").absolutePath)
            goPath?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?.forEach { add(File(it, "bin").absolutePath) }
            goBin?.let { add(it) }
        }.distinct()

        val variables = buildMap {
            put("GOROOT", home)
            goPath?.let { put("GOPATH", it) }
            goBin?.let { put("GOBIN", it) }
        }

        return ProjectTerminalEnv(pathEntries = pathEntries, variables = variables)
    }

    /** 用项目 Go SDK 执行 `go env`，拿到 Go 的有效 GOPATH/GOBIN 默认值。 */
    private fun readGoEnv(goRoot: String): Map<String, String> {
        val executable = File(goRoot, if (SystemInfo.isWindows) "bin/go.exe" else "bin/go")
        if (!executable.isFile) return emptyMap()
        return try {
            val output = CapturingProcessHandler(
                GeneralCommandLine(executable.absolutePath, "env", "GOPATH", "GOBIN"),
            ).runProcess(3_000)
            if (output.exitCode != 0) return emptyMap()
            val lines = output.stdout.split('\n').map { it.trim() }
            buildMap {
                lines.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { put("GOPATH", it) }
                lines.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { put("GOBIN", it) }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
