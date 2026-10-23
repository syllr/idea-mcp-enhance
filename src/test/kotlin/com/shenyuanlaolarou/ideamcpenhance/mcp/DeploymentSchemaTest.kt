package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deployment 工具的**契约测试**：工具描述符的形状 + 风险提示（[deploymentWarnings]）的逻辑 +
 * 返回 JSON 的形状。
 *
 * 全都是纯函数，所以用纯 JUnit 断言，不需要启动 IDE、也不需要真的连一台 SFTP 服务器。
 *
 * 刻意**不测**的部分：`DeploymentAccess` 里对 `com.jetbrains.plugins.webDeployment` 的调用。
 * 那部分既需要插件存在，也要真的有一台服务器，只能靠 `./gradlew runIde` 手工验证
 * （与 `RunConfigAccess` 的处理一致）。
 */
class DeploymentSchemaTest {

    // ---------- 测试数据 ----------

    private fun options(
        autoUpload: String = "NEVER",
        overwriteAll: Boolean = false,
        deleteMissingItems: Boolean = false,
        preserveTimestamps: Boolean = true,
        confirmBeforeUploading: Boolean = false,
        confirmBeforeDeletion: Boolean = false,
        autoUploadExternalChanges: Boolean = false,
        remoteFilesAllowedToDisappearOnAutoupload: Boolean = true,
        excludeMask: String? = null,
    ) = DeploymentOptionsState(
        autoUpload = autoUpload,
        overwriteAll = overwriteAll,
        deleteMissingItems = deleteMissingItems,
        preserveTimestamps = preserveTimestamps,
        confirmBeforeUploading = confirmBeforeUploading,
        confirmBeforeDeletion = confirmBeforeDeletion,
        autoUploadExternalChanges = autoUploadExternalChanges,
        remoteFilesAllowedToDisappearOnAutoupload = remoteFilesAllowedToDisappearOnAutoupload,
        excludeMask = excludeMask,
    )

    private fun server(
        name: String = "sftp",
        needsTransfer: Boolean = true,
        hasCredentials: Boolean = true,
        validationIssue: String? = null,
        deployments: List<DeploymentMappingState> = listOf(
            DeploymentMappingState(localPath = "/local", deployPath = "./", webPath = "/"),
        ),
    ) = DeploymentServerState(
        name = name,
        id = "id-$name",
        projectLevel = false,
        accessType = if (needsTransfer) "SFTP" else "LOCAL",
        host = "example.com",
        port = 22,
        rootFolder = "/srv",
        needsTransfer = needsTransfer,
        hasCredentials = hasCredentials,
        validationIssue = validationIssue,
        isDefault = false,
        deployments = deployments,
    )

    private fun warnings(
        defaultName: String? = "sftp",
        servers: List<DeploymentServerState> = listOf(server()),
        options: DeploymentOptionsState = options(),
    ) = deploymentWarnings(defaultName, servers, options)

    // ---------- 风险提示 ----------

    @Test
    fun `全套健康配置不产生任何警告`() {
        assertEquals(emptyList<String>(), warnings())
    }

    @Test
    fun `没配默认服务器会被点名`() {
        val result = warnings(defaultName = null)
        assertEquals(1, result.size)
        assertTrue(result.single().contains("No default server or group"))
    }

    @Test
    fun `overwriteAll 会提示每次全量重传`() {
        val result = warnings(options = options(overwriteAll = true))
        assertEquals(1, result.size)
        assertTrue(result.single().contains("every file is re-uploaded"))
    }

    @Test
    fun `删多余 搭配 外部变更自动上传 是危险组合`() {
        // 这条是真正会咬人的组合：切 git 分支属于"外部变更"，而 deleteMissingItems 会删远端对应文件。
        val result = warnings(
            options = options(deleteMissingItems = true, autoUploadExternalChanges = true),
        )
        assertEquals(1, result.size)
        assertTrue(result.single().contains("git branch"))
    }

    @Test
    fun `删多余 搭配 删除前确认 会弹模态框`() {
        val result = warnings(
            options = options(deleteMissingItems = true, confirmBeforeDeletion = true),
        )
        assertEquals(1, result.size)
        assertTrue(result.single().contains("modal confirmation"))
    }

    @Test
    fun `自动上传开启时提示切 default 的即时影响`() {
        val result = warnings(options = options(autoUpload = "ALWAYS"))
        assertEquals(1, result.size)
        assertTrue(result.single().contains("Automatic upload is 'ALWAYS'"))
    }

    @Test
    fun `没有凭据的服务器会被点名`() {
        val result = warnings(servers = listOf(server(name = "prod", hasCredentials = false)))
        assertEquals(1, result.size)
        assertTrue(result.single().contains("\"prod\""))
        assertTrue(result.single().contains("modal login dialog"))
    }

    @Test
    fun `配置有问题的服务器会被点名`() {
        val result = warnings(servers = listOf(server(name = "prod", validationIssue = "host is empty")))
        assertEquals(1, result.size)
        assertTrue(result.single().contains("host is empty"))
    }

    @Test
    fun `LOCAL 类型只提示不传输 不再要求凭据`() {
        // needsTransfer=false 时，凭据那条警告带 `needsTransfer &&` 前缀，所以不该出现：
        // 对本地/挂载类型谈"凭据"是噪音。
        val result = warnings(
            servers = listOf(
                server(
                    name = "local",
                    needsTransfer = false,
                    hasCredentials = false,
                    deployments = listOf(DeploymentMappingState("/local", "/", "/")),
                ),
            ),
        )
        assertEquals(1, result.size)
        assertTrue(result.single().contains("does not transfer anything"))
    }

    @Test
    fun `多个问题会全部列出来`() {
        val result = warnings(
            defaultName = null,
            servers = listOf(server(name = "prod", hasCredentials = false)),
            options = options(overwriteAll = true, autoUpload = "ALWAYS"),
        )
        // 4 条：无 default / overwriteAll / 自动上传 / 该服务器无凭据
        assertEquals(4, result.size)
    }

    // ---------- 描述符契约 ----------

    @Test
    fun `list_server_deployments 的入参只有 projectPath`() {
        assertEquals(
            setOf("projectPath"),
            listServerDeploymentsDescriptor.inputSchema.propertiesSchema.keys,
        )
    }

    @Test
    fun `list_server_deployments 的必填项与 toJson 一致`() {
        val required = listServerDeploymentsDescriptor.outputSchema!!.requiredProperties
        val produced = DeploymentServersReport(
            defaultServerOrGroupName = null,
            servers = emptyList(),
            options = options(),
            warnings = emptyList(),
        ).toJson().keys
        assertEquals(produced, required)
    }

    @Test
    fun `list_server_deployments 标记为只读`() {
        assertTrue(listServerDeploymentsDescriptor.annotations!!.readOnlyHint == true)
        assertFalse(listServerDeploymentsDescriptor.annotations!!.destructiveHint == true)
    }

    @Test
    fun `报告 JSON 的字段与形状`() {
        val json = DeploymentServersReport(
            defaultServerOrGroupName = "sftp",
            servers = listOf(server()),
            options = options(),
            warnings = listOf("w1", "w2"),
        ).toJson()

        assertEquals(setOf("defaultServerOrGroupName", "serverCount", "servers", "options", "warnings"), json.keys)
        assertEquals("sftp", json["defaultServerOrGroupName"]!!.jsonPrimitive.content)
        assertEquals(1, json["serverCount"]!!.jsonPrimitive.int)

        val serverJson = json["servers"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "name", "id", "level", "accessType", "host", "port", "rootFolder", "needsTransfer",
                "hasCredentials", "validationIssue", "isDefault", "deployments",
            ),
            serverJson.keys,
        )
        // null 键必须保留：agent 要能区分"键不存在"（工具出错）与"值为 null"（确实读不到）。
        assertTrue(serverJson["validationIssue"] is kotlinx.serialization.json.JsonNull)

        val deploymentJson = serverJson["deployments"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("name"), deploymentJson.keys)
        assertEquals("local", deploymentJson["name"]!!.jsonPrimitive.content)

        assertEquals(setOf("w1", "w2"), json["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    // ---------- upload_to_deployment 契约 ----------

    @Test
    fun `upload 的入参是 deploymentName、mode、waitMs`() {
        assertEquals(
            setOf("deploymentName", "mode", "waitMs", "projectPath"),
            uploadToDeploymentDescriptor.inputSchema.propertiesSchema.keys,
        )
        assertEquals(setOf("deploymentName"), uploadToDeploymentDescriptor.inputSchema.requiredProperties)
    }

    @Test
    fun `status 工具必填 handle，且是只读的`() {
        assertEquals(
            setOf("handle"),
            getDeploymentTaskStatusDescriptor.inputSchema.requiredProperties,
        )
        assertTrue(getDeploymentTaskStatusDescriptor.annotations!!.readOnlyHint == true)
        assertFalse(getDeploymentTaskStatusDescriptor.annotations!!.destructiveHint == true)
    }

    @Test
    fun `upload 与 status 的返回形状一致（同一个 report 结构）`() {
        fun report() = DeploymentTaskReport(
            handle = "dep-1a2b3c4d",
            kind = "upload",
            serverName = "s",
            mode = "mirror",
            roots = emptyList(),
            state = DEPLOYMENT_STATE_IN_PROGRESS,
            elapsedMs = 1000,
            processed = 12,
            total = 318,
            succeeded = false,
            totalProcessed = 0,
            failed = 0,
            counters = emptyMap(),
            warnings = emptyList(),
        ).toJson().keys

        assertEquals(report(), uploadToDeploymentDescriptor.outputSchema!!.requiredProperties)
        assertEquals(report(), getDeploymentTaskStatusDescriptor.outputSchema!!.requiredProperties)
    }

    @Test
    fun `upload 的必填项与 toJson 一致`() {
        val required = uploadToDeploymentDescriptor.outputSchema!!.requiredProperties
        val produced = DeploymentTaskReport(
            handle = "dep-1a2b3c4d",
            kind = "upload",
            serverName = "s",
            mode = "mirror",
            roots = emptyList(),
            state = DEPLOYMENT_STATE_COMPLETED,
            elapsedMs = 12,
            processed = 5,
            total = 5,
            succeeded = true,
            totalProcessed = 5,
            failed = 0,
            counters = emptyMap(),
            warnings = emptyList(),
        ).toJson().keys
        assertEquals(produced, required)
    }

    @Test
    fun `upload 报告 JSON 的字段与形状`() {
        val json = DeploymentTaskReport(
            handle = "dep-1a2b3c4d",
            kind = "upload",
            serverName = "mbptest",
            mode = "mirror",
            roots = listOf("/tmp/local"),
            state = DEPLOYMENT_STATE_COMPLETED,
            elapsedMs = 1540,
            processed = 5,
            total = 5,
            succeeded = true,
            totalProcessed = 5,
            failed = 1,
            counters = mapOf("transfer.details.transferred" to 4, "transfer.details.deleted" to 1),
            warnings = listOf("w"),
        ).toJson()

        assertEquals("dep-1a2b3c4d", json["handle"]!!.jsonPrimitive.content)
        assertEquals("mbptest", json["serverName"]!!.jsonPrimitive.content)
        assertEquals("mirror", json["mode"]!!.jsonPrimitive.content)
        assertEquals(listOf("/tmp/local"), json["localRoots"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(DEPLOYMENT_STATE_COMPLETED, json["state"]!!.jsonPrimitive.content)
        assertEquals(1540, json["elapsedMs"]!!.jsonPrimitive.long)
        assertEquals(5, json["totalProcessed"]!!.jsonPrimitive.int)
        assertEquals(1, json["failed"]!!.jsonPrimitive.int)
        val counters = json["counters"]!!.jsonObject
        assertEquals(setOf("transfer.details.transferred", "transfer.details.deleted"), counters.keys)
        assertEquals(4, counters["transfer.details.transferred"]!!.jsonPrimitive.int)
        assertEquals(1, counters["transfer.details.deleted"]!!.jsonPrimitive.int)
    }

    @Test
    fun `upload 标记为破坏性、非只读、openWorld`() {
        val annotations = uploadToDeploymentDescriptor.annotations!!
        assertFalse(annotations.readOnlyHint == true)
        assertTrue(annotations.destructiveHint == true)
        assertTrue(annotations.openWorldHint == true)
    }

    @Test
    fun `download 的入参是 deploymentName、waitMs`() {
        assertEquals(
            setOf("deploymentName", "waitMs", "projectPath"),
            downloadFromDeploymentDescriptor.inputSchema.propertiesSchema.keys,
        )
        assertEquals(setOf("deploymentName"), downloadFromDeploymentDescriptor.inputSchema.requiredProperties)
    }

    @Test
    fun `download 标记为破坏性（会改本地文件）`() {
        val annotations = downloadFromDeploymentDescriptor.annotations!!
        assertFalse(annotations.readOnlyHint == true)
        assertTrue(annotations.destructiveHint == true)
    }

    @Test
    fun `三个 deployment 工具的返回形状完全一致`() {
        val expected = setOf(
            "handle", "kind", "serverName", "mode", "localRoots", "state",
            "elapsedMs", "processed", "total", "succeeded", "totalProcessed", "failed",
            "counters", "warnings",
        )
        assertEquals(expected, uploadToDeploymentDescriptor.outputSchema!!.requiredProperties)
        assertEquals(expected, downloadFromDeploymentDescriptor.outputSchema!!.requiredProperties)
        assertEquals(expected, getDeploymentTaskStatusDescriptor.outputSchema!!.requiredProperties)
    }
}
