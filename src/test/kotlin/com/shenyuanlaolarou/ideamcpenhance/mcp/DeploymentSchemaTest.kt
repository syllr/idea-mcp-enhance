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
 * `get_deployment_servers` 的契约测试：描述符形状、凭据字段、mapping 的路径拼接。
 *
 * 它是本插件唯一的 deployment 工具 —— 上传 / 下载已经移到 `opencode-idea`，这里只负责
 * 把 SFTP server + 凭据 + 路径映射交出去。
 *
 * 刻意**不测**的部分：`DeploymentAccess` 里对 `com.jetbrains.plugins.webDeployment` 的调用。
 * 那部分要有真的服务器，只能 `./gradlew runIde` 手工验证（2026-10-29 在沙箱里实测过
 * `mcp-test-project` 那一台，确认凭据与 `rootFolder` 都能读到）。
 */
class DeploymentSchemaTest {

    private fun credentials(
        rootFolder: String? = "/data",
        deployPath: String = "/srv/app",
        password: String? = "p",
        authType: String = "PASSWORD",
        privateKeyPath: String? = null,
    ) = DeploymentServerCredentials(
        name = "aidata-middleware",
        sshConfigName = "mbp",
        host = "100.64.0.1",
        port = 22,
        username = "yutao",
        authType = authType,
        password = password,
        passphrase = null,
        privateKeyPath = privateKeyPath,
        rootFolder = rootFolder,
        deployments = listOf(DeploymentMappingState("/local/app", deployPath, "/")),
    )

    @Test
    fun `get_deployment_servers 的入参与输出 schema 对齐`() {
        val descriptor = getDeploymentServersDescriptor
        assertEquals("get_deployment_servers", descriptor.name)
        assertEquals("Deployment", descriptor.category.shortName)
        assertEquals(setOf("projectPath"), descriptor.inputSchema.propertiesSchema.keys)
        assertEquals(setOf("servers"), descriptor.outputSchema!!.requiredProperties)
        assertTrue(descriptor.annotations!!.readOnlyHint == true)
        assertFalse(descriptor.annotations!!.destructiveHint == true)
    }

    @Test
    fun `描述符声明只返回 SFTP server`() {
        // 调用方（opencode-idea）的 remote_exec 只对 SSH 有意义；FTP/WebDAV 没有凭据可用。
        assertTrue(getDeploymentServersDescriptor.description.contains("Only SFTP"))
    }

    @Test
    fun `每个凭据字段都在输出 schema 里`() {
        val required = getDeploymentServersDescriptor.outputSchema!!
            .propertiesSchema["servers"]!!.jsonObject["items"]!!.jsonObject
            .let { it["required"]!!.jsonArray.map { id -> id.jsonPrimitive.content }.toSet() }
        val produced = DeploymentCredentialsReport(servers = listOf(credentials())).toJson()
            .let { it["servers"]!!.jsonArray.first().jsonObject.keys }

        assertEquals(produced, required)
    }

    @Test
    fun `凭据里的 deployment 同时给出两端目录`() {
        val mapping = DeploymentCredentialsReport(servers = listOf(credentials()))
            .toJson()["servers"]!!.jsonArray.first().jsonObject["deployments"]!!.jsonArray.first().jsonObject

        // 只给 name 的话调用方没法拼出两端路径，上传 / 下载就用不了。
        assertEquals(JsonPrimitive("/local/app"), mapping["localPath"])
        // deployPath 是相对 rootFolder 的，返回时必须拼成绝对路径
        assertEquals(JsonPrimitive("/data/srv/app"), mapping["deployPath"])
        // deployment 名 = Deployment 面板里那条 WebServer 的名字，不是 localPath 末段。
        assertEquals(JsonPrimitive("aidata-middleware"), mapping["name"])
    }

    @Test
    fun `deploy 为斜杠时远程目录就是 rootFolder`() {
        val mapping = DeploymentCredentialsReport(
            servers = listOf(credentials(rootFolder = "/Users/yutao/Desktop/data/test", deployPath = "/")),
        ).toJson()["servers"]!!.jsonArray.first().jsonObject["deployments"]!!.jsonArray.first().jsonObject

        assertEquals(JsonPrimitive("/Users/yutao/Desktop/data/test"), mapping["deployPath"])
    }

    @Test
    fun `rootFolder 为空时 deployPath 原样返回`() {
        val mapping = DeploymentCredentialsReport(
            servers = listOf(credentials(rootFolder = null, deployPath = "/srv/app")),
        ).toJson()["servers"]!!.jsonArray.first().jsonObject["deployments"]!!.jsonArray.first().jsonObject

        assertEquals(JsonPrimitive("/srv/app"), mapping["deployPath"])
    }

    @Test
    fun `认证方式缺字段时给出明确的配置问题`() {
        // 正常配置
        assertNull(credentialIssueOf("PASSWORD", "p", null))
        assertNull(credentialIssueOf("KEY_PAIR", null, "/k"))
        assertNull(credentialIssueOf("OPEN_SSH", null, null))

        // 配了密码认证却没有密码（典型原因：IDE 里引用的 SSH 配置被删了）
        assertTrue(credentialIssueOf("PASSWORD", null, null)!!.contains("没有读到密码"))
        assertTrue(credentialIssueOf("KEY_PAIR", null, null)!!.contains("没有私钥路径"))
        assertTrue(credentialIssueOf("UNKNOWN", null, null)!!.contains("无法识别"))
    }

    @Test
    fun `凭据 JSON 保留 null 键而不是省略`() {
        val json = credentials(authType = "KEY_PAIR", password = null, privateKeyPath = "/home/u/.ssh/id_ed25519")
            .toJson()

        // 调用方要能区分"键不存在"（工具出错）和"值为 null"（这种认证方式没有这个字段）
        assertEquals(JsonNull, json["password"])
        assertEquals("/home/u/.ssh/id_ed25519", json["privateKeyPath"]!!.jsonPrimitive.content)
    }
}
