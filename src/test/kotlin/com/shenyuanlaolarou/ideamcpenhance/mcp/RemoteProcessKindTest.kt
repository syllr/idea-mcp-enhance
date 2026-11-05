package com.shenyuanlaolarou.ideamcpenhance.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `Remote Process` 的 kind 推断与容器名解析（纯函数，不需要平台环境）。 */
class RemoteProcessKindTest {

    @Test
    fun `从启动命令推断 kind`() {
        assertEquals("docker", inferKind("docker run -d --name aidata redis:7-alpine"))
        assertEquals("docker", inferKind("sudo docker run -d redis"))
        assertEquals("process", inferKind("java -jar app.jar"))
        assertEquals("process", inferKind("docker compose up -d"))
        assertEquals("process", inferKind(""))
    }

    @Test
    fun `应用日志目录只解析相对路径`() {
        assertNull(resolveAppLogDir(null, "/work"))
        assertEquals("/work/logs", resolveAppLogDir("logs", "/work"))
        assertEquals("/abs/logs", resolveAppLogDir("/abs/logs", "/work"))
        assertEquals("logs", resolveAppLogDir("logs", null))
    }

    @Test
    fun `docker run 自动对齐容器名`() {
        assertEquals(
            "docker run --name c -d -p 8080:8080 img",
            ensureDockerName("docker run -d -p 8080:8080 img", "c"),
        )
        assertEquals(
            "docker run -d --name c -p 8080:8080 img",
            ensureDockerName("docker run -d --name old -p 8080:8080 img", "c"),
        )
        assertEquals(
            "docker run --name c img",
            ensureDockerName("docker run --name=old img", "c"),
        )
    }

    @Test
    fun `从 docker run 里解析容器名`() {
        assertEquals("aidata", containerNameOf("docker run -d --name aidata redis"))
        assertEquals("aidata", containerNameOf("docker run --name=aidata redis"))
        assertNull(containerNameOf("java -jar app.jar"))
    }
}
