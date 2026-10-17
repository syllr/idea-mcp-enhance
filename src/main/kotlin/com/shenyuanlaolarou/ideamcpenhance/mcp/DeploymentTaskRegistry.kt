package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/** 一个 deployment 任务当前的进度（`total` 在收集阶段结束前可能还是估算值，0 表示平台没报）。 */
data class DeploymentProgress(val processed: Int, val total: Int)

/**
 * 记录"每一次 deployment 任务"，让工具能给 AI 一个**可查询的句柄**。
 *
 * 上传和下载都登记在这里——它们是同一类东西（往服务器推 / 从服务器拉），只是方向不同，
 * 所以状态查询用同一个工具，不必为每个方向各造一个。
 *
 * ## 为什么必须是「按任务」而不是「按服务器」
 *
 * 一个部署服务器上会先后发生很多次任务（H1、H2、H3…）。如果状态只按 `serverName` 存，
 * AI 拿到句柄去查，就分不清"它问的是哪一次"——查到的可能是另一次的结果。
 * 所以句柄必须**唯一标识一次任务**，查询结果才不会张冠李戴。
 *
 * ## 两张表各管一件事
 *
 * - [tasks]：`handle → 任务`。**状态的唯一依据**，已完成的任务也保留下来供事后查询。
 * - [runningByServer]：`serverName → handle`。**只用来防并发**——同一台服务器同时只跑一个任务，
 *   第二次调用不会启动第二个，而是把在跑那个的句柄返回给 AI。
 *
 * ## 为什么是 project 级 service 而不是静态变量
 *
 * 这是"某个项目里正在跑的 deployment 任务"，有明确的 IDE 生命周期，跟随项目创建/销毁。
 *
 * ## 清理策略
 *
 * 最多保留 [MAX_REMEMBERED_TASKS] 条**已完成**的任务（AI 可能晚一点才来查），
 * 超出时从最旧的开始丢，**正在跑的永远不丢**。
 */
@Service(Service.Level.PROJECT)
class DeploymentTaskRegistry {

    /** 任务方向：`upload` 推到服务器 / `download` 从服务器拉。 */
    companion object {
        const val KIND_UPLOAD = "upload"
        const val KIND_DOWNLOAD = "download"

        /** 每个项目保留多少条已结束的记录。 */
        const val MAX_REMEMBERED_TASKS = 20
    }

    /** 一次 deployment 任务的登记项。 */
    class Slot internal constructor(
        internal val handle: String,
        internal val kind: String,
        internal val serverName: String,
        internal val mode: String,
        internal val startedAtNanos: Long,
    ) {
        /** 任务真正结束后写入，供事后查询。 */
        @Volatile
        internal var result: DeploymentTaskOutcome? = null

        /** 由任务自己填入，用于轮询时读实时进度。 */
        @Volatile
        internal var progressProvider: (() -> DeploymentProgress)? = null

        /** 只是"结束信号"，真正的结果在 [result] 里。 */
        internal val finished = CompletableDeferred<Unit>()

        internal val isRunning: Boolean get() = !finished.isCompleted
    }

    private val tasks = LinkedHashMap<String, Slot>()
    private val runningByServer = LinkedHashMap<String, String>()

    /**
     * 为 [serverName] 认领一次任务：已有在跑的就加入它，否则新建。
     *
     * @return (slot, isOwner)。`isOwner = true` 表示调用方负责真正启动任务；
     *   `false` 表示这台服务器已有任务在进行中，调用方**只等它**、不要再启一个。
     */
    @Synchronized
    fun acquireOrJoin(
        kind: String,
        serverName: String,
        mode: String,
    ): Pair<Slot, Boolean> {
        runningByServer[serverName]?.let { handle ->
            val existing = tasks[handle]
            if (existing != null && existing.isRunning) return existing to false
            runningByServer.remove(serverName)
        }

        val slot = Slot(
            handle = "dep-" + UUID.randomUUID().toString().take(8),
            kind = kind,
            serverName = serverName,
            mode = mode,
            startedAtNanos = System.nanoTime(),
        )
        tasks[slot.handle] = slot
        runningByServer[serverName] = slot.handle

        // 任务一结束就摘掉"这台服务器正在跑"的标记，下一次调用才能正常发起新任务。
        slot.finished.invokeOnCompletion {
            synchronized(this) {
                if (runningByServer[slot.serverName] == slot.handle) runningByServer.remove(slot.serverName)
                evictCompleted()
            }
        }

        evictCompleted()
        return slot to true
    }

    /** 按句柄查一次任务。查不到返回 null（句柄不存在，或已被清理）。 */
    @Synchronized
    fun lookup(handle: String): Slot? = tasks[handle]

    /** 这个任务跑了多久（毫秒）。 */
    fun elapsedMsOf(slot: Slot): Long = (System.nanoTime() - slot.startedAtNanos) / 1_000_000

    /** 当前进度。 */
    fun progressOf(slot: Slot): DeploymentProgress =
        slot.progressProvider?.invoke() ?: DeploymentProgress(0, 0)

    /**
     * 超出上限时丢最旧的**已完成**任务；正在跑的绝不丢。
     *
     * 丢了就意味着那个句柄再也查不到——所以宁可多留几条（AI 迟来查询）也不误删。
     */
    private fun evictCompleted() {
        val overflow = tasks.size - MAX_REMEMBERED_TASKS
        if (overflow <= 0) return
        val removable = tasks.values.filter { !it.isRunning }.take(overflow)
        for (slot in removable) {
            tasks.remove(slot.handle)
            if (runningByServer[slot.serverName] == slot.handle) runningByServer.remove(slot.serverName)
        }
    }
}
