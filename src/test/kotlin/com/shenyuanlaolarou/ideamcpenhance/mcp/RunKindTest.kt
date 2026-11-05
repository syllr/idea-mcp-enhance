package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.util.JDOMUtil
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这条配置是 job 还是 server」的判定契约。
 *
 * 判错的后果不对称，所以这里把每一种输入都钉死：
 * job 误判成 server → 少等一次（agent 还能查 `history[].exitCode`）；
 * server 误判成 job → 白等满 300 秒预算。
 */
class RunKindTest {

    @Test
    fun `没标记就是 job`() {
        assertEquals(RunKind.JOB, runKindOf(emptyMap()))
    }

    @Test
    fun `显式 false 是 server`() {
        assertEquals(RunKind.SERVER, runKindOf(mapOf(RUN_KIND_ENV to "false")))
    }

    @Test
    fun `显式 true 是 job`() {
        assertEquals(RunKind.JOB, runKindOf(mapOf(RUN_KIND_ENV to "true")))
    }

    @Test
    fun `值的大小写与空格不影响判定`() {
        assertEquals(RunKind.SERVER, runKindOf(mapOf(RUN_KIND_ENV to " FALSE ")))
        assertEquals(RunKind.SERVER, runKindOf(mapOf(RUN_KIND_ENV to "False")))
    }

    @Test
    fun `变量名大小写不敏感`() {
        assertEquals(RunKind.SERVER, runKindOf(mapOf("idea_mcp_enhance_job" to "false")))
    }

    @Test
    fun `认不出来的值当 job 而不是当 server`() {
        // 猜错方向要往"多等一会儿"偏，不能往"提前返回"偏。
        assertEquals(RunKind.JOB, runKindOf(mapOf(RUN_KIND_ENV to "maybe")))
        assertEquals(RunKind.JOB, runKindOf(mapOf(RUN_KIND_ENV to "")))
    }

    @Test
    fun `别的环境变量不影响判定`() {
        assertEquals(RunKind.JOB, runKindOf(mapOf("APP_ENV" to "testenv", "PATH" to "/usr/bin")))
    }
}

/**
 * `.run.xml` 的解析契约。
 *
 * 这里断言的是**字段本身**（agent 按名字取用的东西），不是文案。
 */
class RunConfigurationDefinitionParseTest {

    @Test
    fun `前置任务读出类型 名称与启用状态`() {
        val definition = parseConfiguration(
            """
            <configuration name="aidata@mbp" type="RemoteProcess">
              <method v="2">
                <option name="RunConfigurationTask" enabled="true"
                        run_configuration_name="aidata-build@mbp"
                        run_configuration_type="NodeJSConfigurationType" />
              </method>
            </configuration>
            """.trimIndent(),
        )

        assertEquals(1, definition.beforeRunTasks.size)
        val task = definition.beforeRunTasks.single()
        assertEquals("RunConfigurationTask", task.taskKind)
        assertEquals("aidata-build@mbp", task.configurationName)
        assertEquals("NodeJSConfigurationType", task.configurationType)
        assertTrue(task.enabled)
    }

    @Test
    fun `没写 enabled 时按启用处理`() {
        val definition = parseConfiguration(
            """
            <configuration name="x" type="RemoteProcess">
              <method v="2">
                <option name="RunConfigurationTask"
                        run_configuration_name="build" />
              </method>
            </configuration>
            """.trimIndent(),
        )

        assertTrue(definition.beforeRunTasks.single().enabled)
    }

    @Test
    fun `多个前置任务按文件里的顺序排列`() {
        val definition = parseConfiguration(
            """
            <configuration name="x" type="RemoteProcess">
              <method v="2">
                <option name="RunConfigurationTask" run_configuration_name="first" />
                <option name="RunConfigurationTask" run_configuration_name="second" />
              </method>
            </configuration>
            """.trimIndent(),
        )

        assertEquals(
            listOf("first", "second"),
            definition.beforeRunTasks.map { it.configurationName },
        )
    }

    @Test
    fun `环境变量读出键值`() {
        val definition = parseConfiguration(
            """
            <configuration name="x" type="NodeJSConfigurationType">
              <envs>
                <env name="IDEA_MCP_ENHANCE_JOB" value="false" />
                <env name="APP_ENV" value="testenv" />
              </envs>
            </configuration>
            """.trimIndent(),
        )

        assertEquals("false", definition.envs[RUN_KIND_ENV])
        assertEquals("testenv", definition.envs["APP_ENV"])
    }

    @Test
    fun `没有 method 就没有前置任务`() {
        val definition = parseConfiguration(
            """<configuration name="x" type="RemoteProcess" />""",
        )

        assertTrue(definition.beforeRunTasks.isEmpty())
    }

    @Test
    fun `前置任务的 JSON 带类型 名称 类型 id 与启用状态`() {
        val definition = parseConfiguration(
            """
            <configuration name="x" type="RemoteProcess">
              <method v="2">
                <option name="RunConfigurationTask" enabled="false"
                        run_configuration_name="build"
                        run_configuration_type="NodeJSConfigurationType" />
              </method>
            </configuration>
            """.trimIndent(),
        )
        val json = RunBeforeLaunchTask(
            step = definition.beforeRunTasks.single(),
            recentRuns = emptyList(),
        ).toJson().jsonObject

        assertEquals("RunConfigurationTask", json["taskKind"]!!.jsonPrimitive.content)
        assertEquals("build", json["name"]!!.jsonPrimitive.content)
        assertEquals("NodeJSConfigurationType", json["typeId"]!!.jsonPrimitive.content)
        assertFalse(json["enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    private fun parseConfiguration(xml: String): RunConfigurationDefinition =
        parseRunConfigurationDefinition(JDOMUtil.load(xml))
}
