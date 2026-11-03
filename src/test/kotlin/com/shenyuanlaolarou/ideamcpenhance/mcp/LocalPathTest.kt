package com.shenyuanlaolarou.ideamcpenhance.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Path

/** 项目级 localPath 的相对路径约束与解析（纯函数）。 */
class LocalPathTest {

    @Test
    fun `项目级绝对路径必须拒绝`() {
        assertThrows(DeploymentConfigException::class.java) {
            requireRelativeLocalPath("app", "/Users/me/app")
        }
    }

    @Test
    fun `相对路径按项目根解析`() {
        assertEquals(
            Path.of("/repo", "references", "app").toString(),
            resolveLocalPath("/repo", "references/app"),
        )
        assertEquals("/abs/app", resolveLocalPath("/repo", "/abs/app"))
    }
}
