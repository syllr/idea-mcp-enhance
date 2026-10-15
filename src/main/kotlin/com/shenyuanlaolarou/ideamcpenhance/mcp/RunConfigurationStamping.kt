package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key

/**
 * 把"这段进程属于哪个 Run Configuration"钉在 [ProcessHandler] 自己身上。
 *
 * ## 为什么需要这一层
 *
 * 平台**没有**公开 API 能把 [RunContentDescriptor] 归属到配置：
 *
 * - `RunContentDescriptor.getRunConfigurationName()` —— 从 262 起就带 `@ApiStatus.Internal`
 *   （本仓库 HARD RULE 禁止用内部 API；Plugin Verifier 也会把它算成 "internal API usage"）。
 * - `ExecutionManager.getRunningDescriptors(...)` —— 同样是 `@ApiStatus.Internal`。
 * - `displayName` 是 UI 文本：多实例时会被加后缀，拿它比对配置名必然失配——
 *   而"同一配置有几个实例"恰恰是本插件要回答的核心问题。
 *
 * ## 做法：事件盖章
 *
 * [ExecutionListener] 的 `processStarting` / `processStarted` 同时给出
 * [ExecutionEnvironment]（能从 `runnerAndConfigurationSettings` / `runProfile` 读到配置名）与
 * [ProcessHandler]（`ProcessHandler extends UserDataHolderBase`，公开可写）。
 * 读的时候用 `descriptor.processHandler`（公开）取回**同一个** handler 上的戳。
 *
 * 这套事件由 `ExecutionManagerImpl` 发出，而 IDE 的 Run / Rerun 按钮、内置
 * `execute_run_configuration`、以及本插件的 `rerun` 全都走
 * `ProgramRunnerUtil.executeConfigurationAsync` → `ExecutionManagerImpl.executeConfiguration`，
 * 所以三条来源都能被盖上戳。
 *
 * 状态挂在平台自己的对象上（不建"executionId → 名字"的内存表），因此**没有本插件侧的全局可变状态**；
 * 进程结束后 [ProcessHandler] 仍是同一个实例，戳照样能读到——`stop` / `rerun` 报告里"已结束实例的退出码"
 * 就是靠这一点。
 *
 * ## 覆盖范围
 *
 * 监听在项目打开时挂上（见 [RunConfigurationNameStamper]），所以"项目里跑起来的进程"都会被盖章。
 * 取不到戳的 Run 内容会被跳过（等价于原来的"名字为空就跳过"）：宁可少列一个，也不猜一个错的名字。
 */
private val RUN_CONFIGURATION_NAME: Key<String> =
    Key.create("com.shenyuanlaolarou.ideamcpenhance.runConfigurationName")

/** 把配置名写进进程句柄。名字优先取配置本身（`runnerAndConfigurationSettings`），退化取 `runProfile`。 */
internal fun stampConfigurationName(handler: ProcessHandler, environment: ExecutionEnvironment) {
    val name = environment.runnerAndConfigurationSettings?.name ?: environment.runProfile.name
    handler.putUserData(RUN_CONFIGURATION_NAME, name)
}

/** 读回戳；没有戳（不是我们见过的启动）时返回 null。 */
internal fun RunContentDescriptor.stampedConfigurationName(): String? =
    processHandler?.getUserData(RUN_CONFIGURATION_NAME)

/**
 * 项目打开时挂上监听，给每个新建的进程盖上配置名。
 *
 * 由 `META-INF/mcpserver.xml` 注册（`<postStartupActivity>`）：MCP Server 不存在时这个类根本不会被加载，
 * prompt 那一半不受影响。
 */
internal class RunConfigurationNameStamper : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.messageBus.connect(project).subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
                override fun processStarting(
                    executorId: String,
                    environment: ExecutionEnvironment,
                    handler: ProcessHandler,
                ) = stampConfigurationName(handler, environment)

                override fun processStarted(
                    executorId: String,
                    environment: ExecutionEnvironment,
                    handler: ProcessHandler,
                ) = stampConfigurationName(handler, environment)
            },
        )
    }
}
