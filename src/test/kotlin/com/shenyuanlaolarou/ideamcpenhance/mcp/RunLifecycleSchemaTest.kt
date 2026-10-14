package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 契约测试：工具描述符的形状 + 返回 JSON 的形状 + 参数解析。
 *
 * 这三样都是 agent 直接依赖的东西，比实现细节更接近对外契约，因此用纯 JUnit 断言，
 * 不需要启动 IDE、也不需要真的跑一个 Run Configuration。
 *
 * 刻意**不测**的部分：`RunConfigAccess` 里的平台调用（`ProcessHandler` 状态、stop / rerun 的实际效果）。
 * 那部分只能靠 `./gradlew runIde` 手工验证，硬写成一个跑不动的测试只会变成噪音。
 */
class RunLifecycleSchemaTest {

    private fun tools() = RunLifecycleToolsProvider().getTools()

    private fun toolByName(name: String) =
        tools().first { it.descriptor.name == name }

    // ---------- 工具集合 ----------

    @Test
    fun `provider 暴露且只暴露三个工具`() {
        assertEquals(
            listOf(LIST_TOOL_NAME, STOP_TOOL_NAME, RERUN_TOOL_NAME),
            tools().map { it.descriptor.name },
        )
    }

    @Test
    fun `list 工具只有一个可选参数且输入输出都只面向在跑的`() {
        // 没有 runningOnly / includeTemplates 开关：模板不是实例、永远不会在跑，
        // "只返回在跑的"天然把它排除，再加开关只是把事情搞复杂。
        assertEquals("list_running_configurations", LIST_TOOL_NAME)
        val schema = toolByName(LIST_TOOL_NAME).descriptor.inputSchema
        assertEquals("只应剩 projectPath 一个参数", setOf("projectPath"), schema.propertiesSchema.keys)
        assertTrue("list 没有必填参数", schema.requiredProperties.isEmpty())

        val output = toolByName(LIST_TOOL_NAME).descriptor.outputSchema!!
        assertEquals(setOf("runningCount", "configurations"), output.requiredProperties)
    }

    @Test
    fun `只留活实例的投影会丢掉已结束的实例`() {
        val state = RunConfigurationState(
            name = "console > dev",
            typeId = "ShConfigurationType",
            isTemplate = false,
            uniqueId = "uid-1",
            instances = listOf(instance(1, running = true), instance(2, running = false)),
        )

        val only = state.onlyRunningInstances()
        assertEquals("已结束的实例必须被丢掉", 1, only.instances.size)
        assertEquals(1L, only.instances.single().executionId)
        assertTrue(only.running)
        assertEquals(1, only.runningInstanceCount)
        // 名字与类型等元信息不能被改动
        assertEquals(state.name, only.name)
        assertEquals(state.typeId, only.typeId)
    }

    @Test
    fun `工具名互不相同且是合法 snake_case 标识符`() {
        val names = tools().map { it.descriptor.name }
        assertEquals("工具名重复: $names", names.size, names.toSet().size)
        names.forEach { name ->
            assertTrue("工具名必须是 snake_case: $name", Regex("^[a-z][a-z0-9_]*$").matches(name))
        }
    }

    @Test
    fun `每个工具都有非空描述和非空输入 schema`() {
        for (tool in tools()) {
            val descriptor = tool.descriptor
            assertTrue("${descriptor.name} 缺少描述", descriptor.description.isNotBlank())
            val schema = descriptor.inputSchema
            // McpToolSchema.propertiesSchema 只装 properties，不含根级的 "type": "object"——
            // 根类型由 MCP Server 合成 schema 时自己补上。因此有参数的 schema 一定带属性。
            assertTrue(
                "${descriptor.name} 的 propertiesSchema 是空的",
                schema.propertiesSchema.isNotEmpty(),
            )
        }
    }

    @Test
    fun `只读工具标 readOnly 破坏性工具标 destructive`() {
        fun annotationsOf(name: String) = toolByName(name).descriptor.annotations!!

        assertEquals("$LIST_TOOL_NAME 应标为只读", true, annotationsOf(LIST_TOOL_NAME).readOnlyHint)
        for (name in listOf(STOP_TOOL_NAME, RERUN_TOOL_NAME)) {
            val annotations = annotationsOf(name)
            assertEquals("$name 不应标为只读", false, annotations.readOnlyHint)
            assertEquals("$name 应标为破坏性", true, annotations.destructiveHint)
        }
    }

    @Test
    fun `configurationName 是两个按名工具的必填参数`() {
        for (name in listOf(STOP_TOOL_NAME, RERUN_TOOL_NAME)) {
            val schema = toolByName(name).descriptor.inputSchema
            assertTrue(
                "$name 的 schema 里应有 configurationName 属性",
                schema.propertiesSchema.containsKey("configurationName"),
            )
            assertEquals(
                "$name 必须要求 configurationName",
                setOf("configurationName"),
                schema.requiredProperties,
            )
        }
    }

    @Test
    fun `list 工具没有必填参数`() {
        assertTrue(toolByName(LIST_TOOL_NAME).descriptor.inputSchema.requiredProperties.isEmpty())
    }

    @Test
    fun `每个输入 schema 的属性都带类型与说明`() {
        for (tool in tools()) {
            for ((key, value) in tool.descriptor.inputSchema.propertiesSchema) {
                val property = value.jsonObject
                assertTrue("${tool.descriptor.name}.$key 缺少 type", property["type"] != null)
                assertTrue("${tool.descriptor.name}.$key 缺少 description", property["description"] != null)
            }
        }
    }

    // ---------- 与内置 MCP Server 的对齐 ----------

    @Test
    fun `三个工具都声明 projectPath 且描述与内置工具逐字一致`() {
        for (tool in tools()) {
            val property = tool.descriptor.inputSchema.propertiesSchema["projectPath"]?.jsonObject
            assertTrue("${tool.descriptor.name} 未声明 projectPath", property != null)
            assertEquals("string", property!!["type"]!!.jsonPrimitive.content)
            assertEquals(
                "${tool.descriptor.name} 的 projectPath 描述必须与内置 MCP 工具逐字一致",
                OFFICIAL_PROJECT_PATH_DESCRIPTION,
                property["description"]!!.jsonPrimitive.content,
            )
            assertFalse(
                "${tool.descriptor.name} 不应把 projectPath 放进 required：有 header 时它是可选的",
                tool.descriptor.inputSchema.requiredProperties.contains("projectPath"),
            )
        }
    }

    @Test
    fun `三个工具都声明了输出 schema`() {
        for (tool in tools()) {
            val output = tool.descriptor.outputSchema
            assertTrue("${tool.descriptor.name} 缺少 outputSchema", output != null)
            assertTrue("${tool.descriptor.name} 的 outputSchema 没有属性", output!!.propertiesSchema.isNotEmpty())
            assertTrue("${tool.descriptor.name} 的 outputSchema 没有 required", output.requiredProperties.isNotEmpty())
            for (key in output.requiredProperties) {
                assertTrue(
                    "${tool.descriptor.name}.outputSchema 的 required 里有 properties 中不存在的键 $key",
                    output.propertiesSchema.containsKey(key),
                )
            }
        }
    }

    @Test
    fun `输出 schema 把可空字段写成类型数组`() {
        // 可空字段写成单个 "integer" 会让严格客户端在正常返回（进程还在跑、exitCode 为 null）上校验失败
        fun typesOf(properties: JsonObject, key: String, nested: List<String> = emptyList()) {
            var element = properties[key]!!.jsonObject
            nested.forEach { element = element[it]!!.jsonObject }
            assertEquals(
                "$key 应声明为可空",
                listOf("string", "null"),
                element["type"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }

        val rerun = toolByName(RERUN_TOOL_NAME).descriptor.outputSchema!!.propertiesSchema
        typesOf(rerun, "startFailure")
        typesOf(rerun, "sessionId")
        typesOf(rerun, "fullOutputPath")

        // configurations[] 的 items → 它的 instances[] → 那个 items 的属性
        val configurationItems = toolByName(LIST_TOOL_NAME).descriptor.outputSchema!!
            .propertiesSchema["configurations"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        val instanceProperties = configurationItems["instances"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        for (key in listOf("pid", "exitCode")) {
            assertEquals(
                "$key 应声明为可空（[类型, null]）",
                listOf("integer", "null"),
                instanceProperties[key]!!.jsonObject["type"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `rerun 允许覆盖启动等待窗口`() {
        val schema = toolByName(RERUN_TOOL_NAME).descriptor.inputSchema
        assertTrue(schema.propertiesSchema.containsKey("startTimeoutMs"))
        assertTrue(schema.propertiesSchema.containsKey("stopTimeoutMs"))
        assertEquals(setOf("configurationName"), schema.requiredProperties)
    }

    // ---------- 参数解析 ----------

    @Test
    fun `optionalString 区分缺省 空串与非字符串`() {
        val args = buildJsonObject {
            put("present", JsonPrimitive("value"))
            put("empty", JsonPrimitive(""))
            put("number", JsonPrimitive(42))
            put("null", JsonNull)
        }
        assertEquals("value", args.optionalString("present"))
        assertNull("空串视作没传", args.optionalString("empty"))
        assertNull("非字符串视作没传", args.optionalString("number"))
        assertNull("JsonNull 视作没传", args.optionalString("null"))
        assertNull(args.optionalString("absent"))
    }

    @Test
    fun `optionalInt 对非法值与非正数回落到默认值`() {
        val args = buildJsonObject {
            put("good", JsonPrimitive(1234))
            put("zero", JsonPrimitive(0))
            put("negative", JsonPrimitive(-5))
            put("text", JsonPrimitive("abc"))
        }
        assertEquals(1234, args.optionalInt("good", 5000))
        assertEquals(5000, args.optionalInt("zero", 5000))
        assertEquals(5000, args.optionalInt("negative", 5000))
        assertEquals(5000, args.optionalInt("text", 5000))
        assertEquals(5000, args.optionalInt("absent", 5000))
    }

    @Test
    fun `optionalBoolean 只认显式 true 与 false`() {
        val args = buildJsonObject {
            put("yes", JsonPrimitive(true))
            put("no", JsonPrimitive(false))
            put("junk", JsonPrimitive("yes"))
        }
        assertEquals(true, args.optionalBoolean("yes", false))
        assertEquals(false, args.optionalBoolean("no", true))
        assertEquals(true, args.optionalBoolean("junk", true))
        assertEquals(false, args.optionalBoolean("absent", false))
    }

    @Test
    fun `requiredString 对纯空白视为缺失`() {
        val args = buildJsonObject {
            put("name", JsonPrimitive("   "))
            put("ok", JsonPrimitive("  console > dev  "))
        }
        assertNull(args.requiredString("name"))
        assertNull(args.requiredString("absent"))
        assertEquals("console > dev", args.requiredString("ok"))
    }

    // ---------- 输出 JSON ----------

    @Test
    fun `实例状态保留 null 键而不是省略`() {
        val json = RunInstanceState(
            executionId = 42L,
            displayName = "console > dev",
            running = true,
            terminating = false,
            startNotified = true,
            pid = null,
            exitCode = null,
        ).toJson()

        assertTrue("pid 为 null 时必须仍然存在该键", json.containsKey("pid"))
        assertEquals(JsonNull, json["pid"])
        assertTrue(json.containsKey("exitCode"))
        assertEquals(42L, json["executionId"]!!.jsonPrimitive.long)
        assertTrue(json["running"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `已退出的实例带上退出码`() {
        val json = RunInstanceState(
            executionId = 7L,
            displayName = "Main",
            running = false,
            terminating = false,
            startNotified = true,
            pid = 1234L,
            exitCode = 130,
        ).toJson()

        assertFalse(json["running"]!!.jsonPrimitive.boolean)
        assertEquals(130, json["exitCode"]!!.jsonPrimitive.int)
        assertEquals(1234L, json["pid"]!!.jsonPrimitive.long)
    }

    @Test
    fun `启动中与停止中都算中间态`() {
        // IDE 的 Rerun 按钮就是在这两种状态下灰掉的（FakeRerunAction.isEnabled 判 isStarting / isProcessTerminating）
        val starting = RunInstanceState(1, "dev", running = true, terminating = false, startNotified = false, pid = null, exitCode = null)
        val stopping = RunInstanceState(2, "dev", running = false, terminating = true, startNotified = true, pid = 5L, exitCode = null)
        val up = instance(3, running = true)
        val done = instance(4, running = false)

        assertTrue("跑起来了但还没收到启动通知 = 启动中", starting.inTransientState)
        assertTrue("正在终止 = 停止中", stopping.inTransientState)
        assertFalse("正常在跑不算中间态", up.inTransientState)
        assertFalse("已结束不算中间态", done.inTransientState)

        val state = RunConfigurationState("Main", "Application", false, "uid", listOf(up, stopping))
        assertEquals("应挑出那个正在终止的", 2L, state.transientInstance!!.executionId)
        assertNull(
            "全都不在中间态时没有可挑的",
            RunConfigurationState("Main", "Application", false, "uid", listOf(up, done)).transientInstance,
        )
    }

    @Test
    fun `卡在 terminating 的实例能被识别出来`() {
        val stuck = RunInstanceState(1, "dev", running = false, terminating = true, startNotified = true, pid = null, exitCode = null)
        val normal = RunInstanceState(2, "dev", running = false, terminating = false, startNotified = true, pid = null, exitCode = 0)
        assertTrue(stuck.stuckTerminating)
        assertFalse(normal.stuckTerminating)
    }

    @Test
    fun `配置状态汇总运行中的实例数`() {
        val state = RunConfigurationState(
            name = "console > dev",
            typeId = "js.build_tools.npm",
            isTemplate = false,
            uniqueId = "uid-1",
            instances = listOf(
                instance(1, running = true),
                instance(2, running = true),
                instance(3, running = false),
            ),
        )

        val json = state.toJson()
        assertTrue(state.running)
        assertEquals(2, state.runningInstanceCount)
        assertEquals(2, json["runningInstanceCount"]!!.jsonPrimitive.int)
        assertEquals(3, (json["instances"] as JsonArray).size)
    }

    @Test
    fun `没有实例的配置 running 为 false 且数组为空`() {
        val state = RunConfigurationState("Main", "Application", false, "uid-0", emptyList())
        assertFalse(state.running)
        assertEquals(0, state.runningInstanceCount)

        val json = state.toJson()
        assertEquals(0, (json["instances"] as JsonArray).size)
    }

    @Test
    fun `stop 报告区分 requested 与 forced`() {
        val json = StopReport(
            configurationName = "console > dev",
            matchedInstances = 2,
            requestedStops = 2,
            forcedKills = 1,
            timedOut = true,
            instances = listOf(instance(1, running = true), instance(2, running = false)),
            warnings = listOf("1 instance(s) still running after 5000ms."),
        ).toJson()

        assertEquals(2, json["requestedStops"]!!.jsonPrimitive.int)
        assertEquals(1, json["forcedKills"]!!.jsonPrimitive.int)
        assertTrue(json["timedOut"]!!.jsonPrimitive.boolean)
        assertFalse(json["allTerminated"]!!.jsonPrimitive.boolean)
        assertEquals(1, (json["warnings"] as JsonArray).size)
    }

    @Test
    fun `stop 报告全部终止时 allTerminated 为 true`() {
        val json = StopReport("Main", 1, 1, 0, false, listOf(instance(1, running = false)), emptyList()).toJson()
        assertTrue(json["allTerminated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `rerun 报告输出 mode 的机器可读值`() {
        for (mode in RerunMode.entries) {
            val json = RerunReport("Main", mode, 0, 1, emptyList(), emptyList()).toJson()
            assertEquals(mode.jsonValue, json["mode"]!!.jsonPrimitive.content)
        }
        assertEquals("RESTARTED", RerunMode.RESTARTED.jsonValue)
        assertEquals("STARTED", RerunMode.STARTED.jsonValue)
        assertEquals("NO_OP", RerunMode.NO_OP.jsonValue)
    }

    @Test
    fun `rerun 报告带上启动结果 日志与状态流转`() {
        val json = RerunReport(
            configurationName = "console > dev",
            mode = RerunMode.RESTARTED,
            stoppedInstances = 2,
            runningAfter = 1,
            instances = listOf(instance(3, running = true)),
            warnings = emptyList(),
            started = true,
            sessionId = "console > dev",
            output = "[node] listening on http://127.0.0.1:3201\n",
            fullOutputPath = "/tmp/ij_rerun__console_dev_1.log",
            transition = listOf(
                RunTransitionStep(TransitionPhase.STOPPED, "stop requested for 2 instance(s)", 180),
                RunTransitionStep(TransitionPhase.STARTED, "execution 3, pid 1003", 640),
            ),
        ).toJson()

        // 这是 P1 的核心：runningAfter 必须反映"新进程起来之后"的真实数量，不能再是误导性的 0
        assertTrue(json["started"]!!.jsonPrimitive.boolean)
        assertEquals(1, json["runningAfter"]!!.jsonPrimitive.int)
        assertEquals("console > dev", json["sessionId"]!!.jsonPrimitive.content)
        assertTrue(json["output"]!!.jsonPrimitive.content.contains("listening"))
        assertEquals(JsonNull, json["startFailure"])
        assertTrue("fullOutputPath 为 null 时也必须保留该键", json.containsKey("fullOutputPath"))

        val transition = json["transition"] as JsonArray
        assertEquals(2, transition.size)
        assertEquals("STOPPED", transition[0].jsonObject["phase"]!!.jsonPrimitive.content)
        assertEquals(180L, transition[0].jsonObject["elapsedMs"]!!.jsonPrimitive.long)
        assertEquals("STARTED", transition[1].jsonObject["phase"]!!.jsonPrimitive.content)
        assertEquals("execution 3, pid 1003", transition[1].jsonObject["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rerun 启动失败时 started 为 false 且带原因`() {
        val json = RerunReport(
            configurationName = "Main",
            mode = RerunMode.STARTED,
            stoppedInstances = 0,
            runningAfter = 0,
            instances = emptyList(),
            warnings = listOf("The new process did not come up: timeout"),
            started = false,
            startFailure = "The IDE did not report a started or failed run within 20000ms.",
            transition = listOf(RunTransitionStep(TransitionPhase.START_FAILED, "timeout", 20000)),
        ).toJson()

        assertFalse(json["started"]!!.jsonPrimitive.boolean)
        assertEquals(
            "The IDE did not report a started or failed run within 20000ms.",
            json["startFailure"]!!.jsonPrimitive.content,
        )
        assertEquals("START_FAILED", (json["transition"] as JsonArray)[0].jsonObject["phase"]!!.jsonPrimitive.content)
    }

    @Test
    fun `状态流转的三个节点都有机器可读值`() {
        assertEquals(
            listOf("STOPPED", "STARTED", "START_FAILED"),
            TransitionPhase.entries.map { it.jsonValue },
        )
    }

    @Test
    fun `找不到配置时错误文案带上候选名`() {
        val message = UnknownConfigurationReport("server", listOf("client", "server-dev")).message
        assertTrue(message, message.contains("server"))
        assertTrue(message, message.contains("client, server-dev"))

        val json = UnknownConfigurationReport("server", listOf("client", "server-dev")).toJson()
        assertEquals(2, (json["availableNames"] as JsonArray).size)
    }

    @Test
    fun `项目完全没有配置时错误文案不给出空列表`() {
        val message = UnknownConfigurationReport("server", emptyList()).message
        assertTrue(message, message.contains("no run configurations at all"))
    }

    // ---------- 辅助 ----------

    private fun instance(executionId: Long, running: Boolean) = RunInstanceState(
        executionId = executionId,
        displayName = "instance-$executionId",
        running = running,
        terminating = false,
        startNotified = true,
        pid = 1000L + executionId,
        exitCode = null,
    )

    private companion object {
        /**
         * 内置 MCP 工具 `projectPath` 描述的**逐字副本**。
         *
         * 故意在测试里再抄一遍而不是引用生产代码里的常量：这条文案是与内置工具的对齐契约，
         * 两边各写一份才能发现"只有一边被改动了"。
         */
        const val OFFICIAL_PROJECT_PATH_DESCRIPTION =
            " The project path. Pass this value ALWAYS if you are aware of it. It reduces numbers of ambiguous calls. \n" +
                " In the case you know only the current working directory you can use it as the project path.\n" +
                " If you're not aware about the project path you can ask user about it."
    }
}