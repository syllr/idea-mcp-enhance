package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两个 Deployment 工具的契约测试：描述符形状、凭据字段、sync 结果字段。
 *
 * 刻意**不测**的部分：`DeploymentAccess` 里对 `SshConfigManager` / `com.jetbrains.plugins.webDeployment`
 * 的调用。那部分要有真的 SSH 配置和 Deployment 面板，只能 `./gradlew runIde` 手工验证。
 */
class DeploymentSchemaTest {

    private fun credentials(
        password: String? = "p",
        authType: String = "PASSWORD",
        privateKeyPath: String? = null,
    ) = DeploymentServerCredentials(
        name = "mbp",
        host = "100.64.0.1",
        port = 22,
        username = "yutao",
        authType = authType,
        password = password,
        passphrase = null,
        privateKeyPath = privateKeyPath,
    )

    @Test
    fun `get_ssh_servers 的入参与输出 schema 对齐`() {
        val descriptor = getSshServersDescriptor
        assertEquals("get_ssh_servers", descriptor.name)
        assertEquals("Deployment", descriptor.category.shortName)
        assertEquals(setOf("projectPath"), descriptor.inputSchema.propertiesSchema.keys)
        assertEquals(setOf("servers"), descriptor.outputSchema!!.requiredProperties)
        assertTrue(descriptor.annotations!!.readOnlyHint == true)
    }

    @Test
    fun `每个凭据字段都在输出 schema 里`() {
        val required = getSshServersDescriptor.outputSchema!!
            .propertiesSchema["servers"]!!.jsonObject["items"]!!.jsonObject
            .let { it["required"]!!.jsonArray.map { id -> id.jsonPrimitive.content }.toSet() }
        val produced = DeploymentCredentialsReport(servers = listOf(credentials())).toJson()
            .let { it["servers"]!!.jsonArray.first().jsonObject.keys }

        assertEquals(produced, required)
    }

    @Test
    fun `凭据 JSON 保留 null 键而不是省略`() {
        val json = credentials(authType = "KEY_PAIR", password = null, privateKeyPath = "/home/u/.ssh/id_ed25519")
            .toJson()

        // 调用方要能区分"键不存在"（工具出错）和"值为 null"（这种认证方式没有这个字段）
        assertEquals(JsonNull, json["password"])
        assertEquals("/home/u/.ssh/id_ed25519", json["privateKeyPath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sync_deployments 的入参与输出 schema 对齐`() {
        val descriptor = syncDeploymentsDescriptor
        assertEquals("sync_deployments", descriptor.name)
        assertEquals(setOf("projectPath", "server", "deployments"), descriptor.inputSchema.propertiesSchema.keys)
        assertEquals(
            setOf("created", "updated", "unchanged", "orphaned"),
            descriptor.outputSchema!!.requiredProperties,
        )
        assertFalse(descriptor.annotations!!.readOnlyHint == true)
        // 只增/改不删：destructive 必须是 false，否则客户端会把它当成破坏性操作。
        assertFalse(descriptor.annotations!!.destructiveHint == true)
    }

    @Test
    fun `sync 报告把 orphaned 和三条结果分开输出`() {
        val json = DeploymentSyncReport(
            created = listOf("a"),
            updated = listOf("b"),
            unchanged = listOf("c"),
            orphaned = listOf("old"),
        ).toJson()

        assertEquals(JsonPrimitive("a"), json["created"]!!.jsonArray.first())
        assertEquals(JsonPrimitive("b"), json["updated"]!!.jsonArray.first())
        assertEquals(JsonPrimitive("c"), json["unchanged"]!!.jsonArray.first())
        assertEquals(JsonPrimitive("old"), json["orphaned"]!!.jsonArray.first())
    }

    @Test
    fun `认证方式缺字段时给出明确的配置问题`() {
        // 正常配置
        assertNull(credentialIssueOf("PASSWORD", "p", null))
        assertNull(credentialIssueOf("KEY_PAIR", null, "/k"))
        assertNull(credentialIssueOf("OPEN_SSH", null, null))

        // 配了密码认证却没有密码
        assertTrue(credentialIssueOf("PASSWORD", null, null)!!.contains("没有读到密码"))
        assertTrue(credentialIssueOf("KEY_PAIR", null, null)!!.contains("没有私钥路径"))
        assertTrue(credentialIssueOf("UNKNOWN", null, null)!!.contains("无法识别"))
    }
}
