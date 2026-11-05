package com.shenyuanlaolarou.ideamcpenhance.mcp

/**
 * 一条 Run Configuration 是「跑完就退的一次性任务」还是「一直跑的服务」。
 *
 * ## 为什么需要这个区分
 *
 * `rerun_run_configuration` 对两者的正确行为相反：
 *
 * - **job**（构建脚本、测试、迁移）：要等到进程退出，把 `exitCode` 与完整输出带回去；
 * - **server**（dev server、远端进程）：一启动就得返回，否则调用方会一直挂着。
 *
 * 平台**没有**任何 API 能回答这个问题：`console-dev`（`npm run dev`）与
 * `aidata-build@mbp`（跑一个脚本）在类型层面完全一样。所以只能由配置自己声明。
 *
 * ## 为什么声明写在 `<envs>` 里
 *
 * `.run.xml` 里塞自定义 `<option>` 是不行的：`RunnerAndConfigurationSettingsImpl.writeScheme()`
 * 每次都从 `Element("configuration")` 重新拼，`serializeConfigurationInto` 只写 options 类里
 * 声明过的字段 —— 未知标签会在用户下次在 IDE 里保存该配置时**静默消失**。
 *
 * 而 `<envs>` 是配置类型**自己**序列化的字段（NodeJS 走 `writeConfiguration` →
 * `EnvironmentVariablesData.writeExternalNoDefault`；npm 走 `NpmRunSettings.writeExternal`），
 * IDE 改配置、点 OK 都不会丢。代价是这个变量会真的注入进进程环境 —— 但它同时出现在
 * IDE 的 Environment variables 字段里，可见、可编辑，不是黑盒。
 *
 * ## 缺省是 job
 *
 * 忘标记的代价不对称：job 误判成 server 只是少等一次（agent 再查 `inspect` 的
 * `history[].exitCode` 即可）；server 误判成 job 则会白等满 300 秒预算。
 * 两者都会返回，但「默认 job」让「要结果」这个主诉求永远成立。
 */
enum class RunKind(val id: String) {
    /** 跑完就退：等到进程终止，回传 `exitCode` 与完整输出。 */
    JOB("job"),

    /** 一直跑：进程一起来就返回。 */
    SERVER("server"),
}

/** 声明 run kind 的环境变量名。 */
internal const val RUN_KIND_ENV = "IDEA_MCP_ENHANCE_JOB"

/**
 * 从配置的环境变量里读 run kind。
 *
 * 只认 [RUN_KIND_ENV] 这一个键：
 * - `true` → [RunKind.JOB]
 * - `false` → [RunKind.SERVER]
 * - 缺省 / 认不出来 → [RunKind.JOB]（见枚举上的说明）
 */
internal fun runKindOf(envs: Map<String, String>): RunKind {
    val raw = envs.entries
        .firstOrNull { it.key.equals(RUN_KIND_ENV, ignoreCase = true) }
        ?.value
        ?.trim()
        ?.lowercase()
        ?: return RunKind.JOB
    return if (raw == "false") RunKind.SERVER else RunKind.JOB
}
