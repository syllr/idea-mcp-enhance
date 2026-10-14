package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 记录"本插件正在对哪些 Run Configuration 执行 rerun"，把**同一配置的并发 rerun** 挡在平台之外。
 *
 * ## 为什么必须挡
 *
 * 平台的 `ProgramRunnerUtil.executeConfigurationAsync` 在对一个**已在运行 / 正在启动**的配置再次执行时，
 * 会走 `RunManagerConfig.isRestartRequiresConfirmation()` 那条路弹一个**模态确认框**
 * （`CONFIRM_RERUN_KEY`）。GUI 上你碰不到它——Rerun 按钮在中间态本来就是灰的；但在 MCP 这条无头路径上
 * 没有人去点它，于是这次工具调用一直挂着，直到传输层超时。实测结果：**约 20 秒后客户端拿到 HTTP 404 空响应**，
 * 非常难读的失败。
 *
 * 实测边界（都验过）：
 * - 同一配置并发 rerun → 一个正常返回，另一个 20s 后被 404 ❌
 * - 不同配置并发 rerun → 两个都正常 ✅
 * - rerun 进行中，另一会话调 list → 正常 ✅（本插件不会卡住服务器）
 *
 * 所以这是一个**只对同配置**生效的守护，粒度与 GUI 的置灰语义一致。
 *
 * ## 为什么是项目级 service 而不是 object 里的静态状态
 *
 * 多项目之间天然隔离；项目关掉即随之消失，不会把"正在飞行中"的标志漏给下一次打开的项目。
 */
@Service(Service.Level.PROJECT)
class RunLifecycleInFlight {

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 抢占某个配置名；已经有同名 rerun 在飞行中时返回 false。 */
    fun tryAcquire(configurationName: String): Boolean = inFlight.add(configurationName)

    /** 必须在 `finally` 里调用，否则该配置名会被永久占住、再也 rerun 不了。 */
    fun release(configurationName: String) {
        inFlight.remove(configurationName)
    }
}
