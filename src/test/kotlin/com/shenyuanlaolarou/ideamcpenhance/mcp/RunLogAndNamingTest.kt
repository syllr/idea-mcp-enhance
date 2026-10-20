package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定义态 + 运行日志的纯契约测试。
 *
 * 覆盖的三类东西都是 agent 直接看到的：
 *
 * - **命名文法**：`win@base@env` 解析成什么
 * - **日志键与文件名**：历史日志靠它被找到，格式错一位就"历史消失"
 * - **JSON 形状**：列表精简形状、inspect 全貌、null 键保留
 *
 * 不测平台调用（`ProcessListener`、文件监听、RunManager）—— 那些只能靠 `runIde` 手工验证。
 */
class RunLogAndNamingTest {

    // ---------- 命名文法 ----------

    @Test
    fun `不带前缀的名字是 mac-linux 且没有环境`() {
        val naming = parseRunConfigurationName("console-web")!!
        assertEquals(PLATFORM_MAC_LINUX, naming.platform)
        assertEquals("console-web", naming.base)
        assertNull(naming.env)
    }

    @Test
    fun `win 前缀是平台段`() {
        val naming = parseRunConfigurationName("win@console-web")!!
        assertEquals(PLATFORM_WINDOWS, naming.platform)
        assertEquals("console-web", naming.base)
        assertNull(naming.env)
    }

    @Test
    fun `两段里不是 win 的首段并进 base 之外的末段是环境`() {
        val naming = parseRunConfigurationName("console-web@testenv")!!
        assertEquals(PLATFORM_MAC_LINUX, naming.platform)
        assertEquals("console-web", naming.base)
        assertEquals("testenv", naming.env)
    }

    @Test
    fun `三段是平台 + base + 环境`() {
        val naming = parseRunConfigurationName("win@console-web@yutairstandalone")!!
        assertEquals(PLATFORM_WINDOWS, naming.platform)
        assertEquals("console-web", naming.base)
        assertEquals("yutairstandalone", naming.env)
    }

    @Test
    fun `不认识的平台段不会被丢掉`() {
        val naming = parseRunConfigurationName("a@b@c")!!
        assertEquals("a@b", naming.base)
        assertEquals("c", naming.env)
        assertEquals(PLATFORM_MAC_LINUX, naming.platform)
    }

    @Test
    fun `空名字解析不出来`() {
        assertNull(parseRunConfigurationName(""))
        assertNull(parseRunConfigurationName("   "))
        assertNull(parseRunConfigurationName("@"))
        assertNull(parseRunConfigurationName("win@"))
    }

    @Test
    fun `命名解析结果按三个键输出`() {
        val json = parseRunConfigurationName("win@console-web@testenv")!!.toJson()
        assertEquals(setOf("platform", "base", "env"), json.keys)
        assertEquals("win", json["platform"]!!.jsonPrimitive.content)
        assertEquals("testenv", json["env"]!!.jsonPrimitive.content)
    }

    // ---------- 日志键与文件名 ----------

    @Test
    fun `日志键是稳定的十六进制且区分项目与配置名`() {
        val key = runLogKey("/p", "console-web")
        assertEquals("同样的输入必须得到同样的键", key, runLogKey("/p", "console-web"))
        assertEquals(16, key.length)
        assertTrue("只允许小写十六进制: $key", key.all { it in "0123456789abcdef" })

        assertTrue("不同项目不能共用键", key != runLogKey("/p2", "console-web"))
        assertTrue("不同配置不能共用键", key != runLogKey("/p", "console_web"))
        assertTrue("大小写不同不能共用键（macOS 文件系统不区分大小写）", key != runLogKey("/p", "Console-Web"))
    }

    @Test
    fun `日志文件名能原样解析回来`() {
        val key = runLogKey("/p", "console-web")
        val name = runLogFileName(key, 1792461973004, "a1b2")
        assertEquals("ij_enhance_run__${key}_1792461973004_a1b2.log", name)

        val parsed = parseRunLogFileName(name)!!
        assertEquals(key, parsed.key)
        assertEquals(1792461973004, parsed.startedAtMillis)
        assertEquals("a1b2", parsed.randomSuffix)
    }

    @Test
    fun `官方的日志文件名不会被认成我们的`() {
        // 官方前缀是 ij_run__，名字部分是不可逆的 sanitize 结果 —— 认错就会把别人的日志当成这个配置的。
        assertNull(parseRunLogFileName("ij_run__console-web_1792461973004.log"))
        assertNull(parseRunLogFileName("ij_rerun__console-web_abc.log"))
        assertNull(parseRunLogFileName("ij_enhance_run__short_1792461973004_a1b2.log"))
        assertNull(parseRunLogFileName("ij_enhance_run__${runLogKey("/p", "x")}_abc_a1b2.log"))
        assertNull(parseRunLogFileName("ij_enhance_run__${runLogKey("/p", "x")}_1792461973004_zzzz.log"))
    }

    @Test
    fun `sidecar 名字与日志同名换后缀`() {
        val log = runLogFileName(runLogKey("/p", "x"), 1, "abcd")
        assertEquals(log.removeSuffix(".log") + ".json", runLogSidecarFileName(log))
    }

    @Test
    fun `历史条目的 JSON 保留 null 键`() {
        val entry = RunLogEntry(
            startedAtMillis = 1792450000000,
            logFile = "/tmp/a.log",
            endedAtMillis = null,
            pid = null,
            exitCode = null,
        )
        val json = entry.toJson()
        assertEquals(
            setOf("startedAt", "startedAtIso", "endedAt", "durationMs", "pid", "exitCode", "logFile"),
            json.keys,
        )
        assertTrue("sidecar 缺失时 endedAt 必须是 null 而不是被省略", json["endedAt"] is JsonNull)
        assertTrue(json["durationMs"] is JsonNull)
        assertTrue(json["pid"] is JsonNull)
        assertTrue(json["exitCode"] is JsonNull)
        assertEquals("/tmp/a.log", json["logFile"]!!.jsonPrimitive.content)
        assertEquals("2026-10-19T22:46:40Z", json["startedAtIso"]!!.jsonPrimitive.content)
    }

    @Test
    fun `有 sidecar 时算出持续时间`() {
        val entry = RunLogEntry(1000, "/tmp/a.log", endedAtMillis = 61000, pid = 42, exitCode = 1)
        val json = entry.toJson()
        assertEquals(60000L, entry.durationMillis)
        assertEquals(60000L, json["durationMs"]!!.jsonPrimitive.long)
        assertEquals(1, json["exitCode"]!!.jsonPrimitive.content.toInt())
    }

    // ---------- 列表的精简形状 ----------

    private fun instance(
        executionId: Long,
        running: Boolean = true,
        terminating: Boolean = false,
        startNotified: Boolean = true,
        logFile: String? = null,
    ) = RunInstanceState(
        executionId = executionId,
        displayName = "console > dev",
        running = running,
        terminating = terminating,
        startNotified = startNotified,
        pid = 2100,
        exitCode = null,
        logFile = logFile,
    )

    @Test
    fun `实例三态由 running terminating startNotified 合并而来`() {
        assertEquals(INSTANCE_STATE_RUNNING, instance(1).state)
        assertEquals(INSTANCE_STATE_TERMINATING, instance(1, terminating = true).state)
        assertEquals(INSTANCE_STATE_STARTING, instance(1, startNotified = false).state)
    }

    @Test
    fun `列表的配置形状只有名字 类型 实例`() {
        val state = RunConfigurationState(
            name = "console-web",
            typeId = "ShConfigurationType",
            isTemplate = false,
            uniqueId = "uid",
            instances = listOf(instance(1, logFile = "/tmp/a.log")),
        )
        val json = state.toListJson()
        assertEquals(setOf("name", "type", "instances"), json.keys)
        assertFalse("派生计数不该出现在列表里", json.containsKey("runningInstanceCount"))
    }

    @Test
    fun `列表的实例形状带日志路径且没有恒定值字段`() {
        val json = instance(7, logFile = "/tmp/a.log").toListJson()
        assertEquals(setOf("executionId", "state", "pid", "logFile"), json.keys)
        assertEquals(7L, json["executionId"]!!.jsonPrimitive.long)
        assertEquals("running", json["state"]!!.jsonPrimitive.content)
        assertEquals("/tmp/a.log", json["logFile"]!!.jsonPrimitive.content)
        assertFalse("活着的实例没有退出码，不该给这个键", json.containsKey("exitCode"))
        assertFalse(json.containsKey("displayName"))
    }

    @Test
    fun `没采到日志时 logFile 是 null 而不是省略`() {
        val json = instance(7, logFile = null).toListJson()
        assertTrue(json["logFile"] is JsonNull)
    }

    // ---------- inspect 的全貌 ----------

    @Test
    fun `inspect 的 JSON 带定义 实例与历史且没有派生计数`() {
        val inspection = RunConfigurationInspection(
            configurationName = "win@console-web@testenv",
            typeId = "ShConfigurationType",
            typeDisplayName = "Shell Script",
            typeKnown = true,
            storage = RunConfigurationStorage.RUN_FILE,
            storagePath = "/p/.run/win@console-web@testenv.run.xml",
            shared = true,
            isTemplate = false,
            canRun = true,
            canRunBlockedReason = null,
            naming = parseRunConfigurationName("win@console-web@testenv"),
            attributes = emptyMap(),
            options = mapOf("SCRIPT_TEXT" to "pnpm dev"),
            envs = mapOf("APP_ENV" to "testenv"),
            beforeLaunchSteps = listOf(RunBeforeLaunchStep("RunConfigurationRunTask", "build")),
            configurationXml = "<configuration />",
            instances = listOf(instance(1, logFile = "/tmp/a.log")),
            history = listOf(RunLogEntry(1000, "/tmp/old.log", 2000, 42, 0)),
            warnings = emptyList(),
        )
        val json = inspection.toJson()

        assertEquals(
            setOf(
                "configurationName", "type", "typeDisplayName", "typeKnown", "storage", "isTemplate", "canRun",
                "canRunBlockedReason", "naming", "attributes", "options", "envs", "beforeLaunchSteps",
                "configurationXml", "instances", "history", "warnings",
            ),
            json.keys,
        )
        assertFalse("实例数就是数组长度，不该再给计数", json.containsKey("runningInstanceCount"))
        assertFalse(json.containsKey("historyCount"))

        val storage = json["storage"]!!.jsonObject
        assertEquals(setOf("kind", "path", "shared"), storage.keys)
        assertEquals("runFile", storage["kind"]!!.jsonPrimitive.content)

        val naming = json["naming"]!!.jsonObject
        assertEquals("win", naming["platform"]!!.jsonPrimitive.content)
        assertEquals("console-web", naming["base"]!!.jsonPrimitive.content)
        assertEquals("testenv", naming["env"]!!.jsonPrimitive.content)

        val history = json["history"]!!.jsonArray.single().jsonObject
        assertFalse("历史日志不会再涨，不需要内联尾巴", history.containsKey("tail"))
        assertFalse(history.containsKey("logTail"))
        assertEquals("/tmp/old.log", history["logFile"]!!.jsonPrimitive.content)
    }

    // ---------- 工具集合与 schema ----------

    private fun definitionTools() = RunConfigDefinitionToolsProvider().getTools()

    @Test
    fun `定义态 provider 暴露四个工具`() {
        assertEquals(
            listOf(INSPECT_TOOL_NAME, CREATE_DEFINITION_TOOL_NAME, UPDATE_TOOL_NAME, DELETE_TOOL_NAME),
            definitionTools().map { it.descriptor.name },
        )
    }

    @Test
    fun `四个工具都声明了 projectPath 且只有配置名必填`() {
        val tools = definitionTools()
        for (tool in tools) {
            val schema = tool.descriptor.inputSchema
            assertTrue(
                "${tool.descriptor.name} 必须声明 projectPath（没配 header 的客户端只能靠它）",
                schema.propertiesSchema.containsKey("projectPath"),
            )
            assertEquals(
                "${tool.descriptor.name} 只有配置名是必填的",
                setOf("configurationName"),
                schema.requiredProperties,
            )
            assertNotNull(tool.descriptor.outputSchema)
        }
    }

    @Test
    fun `删除是破坏性的而 inspect 是只读的`() {
        val annotations = definitionTools().associate { tool ->
            tool.descriptor.name to tool.descriptor.annotations
        }
        assertTrue(annotations.getValue(INSPECT_TOOL_NAME)?.readOnlyHint == true)
        assertTrue(annotations.getValue(DELETE_TOOL_NAME)?.destructiveHint == true)
        assertFalse(annotations.getValue(CREATE_DEFINITION_TOOL_NAME)?.readOnlyHint == true)
    }

    @Test
    fun `描述里说清了不要自己写 run xml`() {
        val create = definitionTools().first { it.descriptor.name == CREATE_DEFINITION_TOOL_NAME }.descriptor
        assertTrue(create.description.contains(".run/<configurationName>.run.xml"))
        assertTrue(create.description.contains("Do not create or edit that file yourself"))
    }
}
