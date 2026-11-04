package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * 把项目配置的 Go SDK 注入本地终端 PATH/GOROOT/GOPATH/GOBIN。
 *
 * **Go 插件的 API 全部走反射**（见 [GoApi]）：Go 插件不随 IDEA 一起分发，jar 里只要有类直接
 * import `com.goide.*`，Plugin Verifier 就会判 "binary incompatible" —— 在没装 Go 插件的 IDEA 上
 * 打开就是 NoClassDefFoundError。config-file 的 optional 依赖只能挡住**运行时**加载这些类，
 * 挡不住 verifier 的静态扫描。
 */
class GoTerminalEnvRegistrar : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ProjectTerminalEnvRegistry>().register(GoTerminalEnvProvider)
    }
}

/**
 * Go 插件 API 的反射入口。Go 插件不在时每个方法都返回 null —— provider 什么都不贡献。
 */
private object GoApi {

    /** Go 插件没装 / 版本变了 → 拿不到就当没有。 */
    private fun type(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (_: Throwable) {
        null
    }

    /**
     * 调一个无参方法。Kotlin 的 `object` 方法是实例方法（`INSTANCE`），也可能被标成静态 —— 两种都试。
     */
    private fun invokeNoArg(cls: Class<*>, method: String): String? {
        val target = cls.methods.firstOrNull { it.name == method && it.parameterCount == 0 } ?: return null
        val instance = try {
            cls.getField("INSTANCE").get(null)
        } catch (_: Throwable) {
            null
        }
        return try {
            target.invoke(instance) as? String
        } catch (_: Throwable) {
            null
        }
    }

    /** `GoEnvironmentUtil.retrieveGoPathFromEnvironment()` */
    fun goPathFromEnvironment(): String? =
        type("com.goide.GoEnvironmentUtil")?.let { invokeNoArg(it, "retrieveGoPathFromEnvironment") }

    /** `GoEnvironmentUtil.retrieveGoBinFromEnvironment()` */
    fun goBinFromEnvironment(): String? =
        type("com.goide.GoEnvironmentUtil")?.let { invokeNoArg(it, "retrieveGoBinFromEnvironment") }

    /** `GoSdkService.getInstance(project).getSdk(module)?.homePath` */
    fun sdkHomePath(project: Project): String? {
        val cls = type("com.goide.sdk.GoSdkService") ?: return null
        val service = try {
            cls.getMethod("getInstance", Project::class.java).invoke(null, project)
        } catch (_: Throwable) {
            null
        } ?: return null
        val module = ModuleManager.getInstance(project).modules.firstOrNull()
        val getSdk = cls.methods.firstOrNull { it.name == "getSdk" && it.parameterCount == 1 } ?: return null
        val sdk = try {
            getSdk.invoke(service, module)
        } catch (_: Throwable) {
            null
        } ?: return null
        val home = sdk.javaClass.methods.firstOrNull { it.name == "getHomePath" } ?: return null
        return try {
            home.invoke(sdk) as? String
        } catch (_: Throwable) {
            null
        }
    }
}

private object GoTerminalEnvProvider : ProjectTerminalEnvProvider {
    override val id = ProjectEnvProvider.Go

    override fun extraEnv(project: Project): ProjectTerminalEnv {
        val home = GoApi.sdkHomePath(project)?.takeIf { it.isNotBlank() } ?: return ProjectTerminalEnv()

        val configuredGoPath = GoApi.goPathFromEnvironment()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // IDE/环境变量没有 GOPATH 时，用 Go SDK 自己的有效配置兜底。
        val goEnv = if (configuredGoPath == null) readGoEnv(home) else emptyMap()
        val goPath = configuredGoPath ?: goEnv["GOPATH"]

        val rawGoBin = GoApi.goBinFromEnvironment()
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
