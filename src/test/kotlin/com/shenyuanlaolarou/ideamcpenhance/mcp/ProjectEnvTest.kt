package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `get_project_env` 的契约测试：返回形状、provider 顺序、描述符。
 *
 * 纯 JUnit —— 不碰平台，provider 各自读 IDE 模型的行为只能 `runIde` 手工验证。
 */
class ProjectEnvTest {

    @Test
    fun `get_project_env 的形状与分类`() {
        val descriptor = getProjectEnvDescriptor
        assertEquals("get_project_env", descriptor.name)
        assertEquals("Environment", descriptor.category.shortName)
        // 它只按 header 里的项目路径取数，没有必填参数
        assertEquals(emptySet<String>(), descriptor.inputSchema.requiredProperties)
        assertEquals(
            setOf("projectPath", "PATH", "variables"),
            descriptor.outputSchema!!.propertiesSchema.keys,
        )
    }

    @Test
    fun `ProjectEnv 的两层形状与固定顺序`() {
        val json = ProjectEnv(
            path = "/jdk/bin:/node/bin",
            variables = mapOf("GOROOT" to "/go", "VIRTUAL_ENV" to "/venv"),
        ).toJson()

        assertEquals(JsonPrimitive("/jdk/bin:/node/bin"), json["PATH"])
        val variables = json["variables"]!!.jsonObject
        assertEquals(JsonPrimitive("/go"), variables["GOROOT"])
        assertEquals(JsonPrimitive("/venv"), variables["VIRTUAL_ENV"])

        // PATH 的顺序必须稳定：provider 的注册顺序不可控，结算按枚举声明顺序。
        assertEquals(
            listOf(
                ProjectEnvProvider.Java,
                ProjectEnvProvider.Node,
                ProjectEnvProvider.Go,
                ProjectEnvProvider.Maven,
                ProjectEnvProvider.Python,
            ),
            ProjectEnvProvider.entries.toList(),
        )
    }
}
