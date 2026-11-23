package com.shenyuanlaolarou.ideamcpenhance.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Path

/**
 * 项目级 `localPath` 的相对路径约束与解析（纯函数）。
 *
 * 判定刻意不依赖本机文件系统语义：`hosts.json` 跟着仓库走，会在 Windows / macOS /
 * Linux 三种机器上被读到，所以「三种平台的绝对写法」都必须被拒。
 */
class LocalPathTest {

    @Test
    fun `项目级绝对路径必须拒绝`() {
        assertThrows(DeploymentConfigException::class.java) {
            requireRelativeLocalPath("app", "/Users/me/app")
        }
    }

    @Test
    fun `Windows 写法也要按同样规则判定`() {
        // 盘符与 UNC 在 macOS 上不算绝对路径，但语义上一样不能出现在项目级 hosts.json 里。
        assertThrows(DeploymentConfigException::class.java) {
            requireRelativeLocalPath("app", "C:\\work\\app")
        }
        assertThrows(DeploymentConfigException::class.java) {
            requireRelativeLocalPath("app", "//server/share/app")
        }
        assertThrows(DeploymentConfigException::class.java) {
            requireRelativeLocalPath("app", "~/app")
        }
        // 仓库里只存 `/`：Windows 写法也直接报错，不做兼容。
        assertThrows(DeploymentConfigException::class.java) {
            requireRelativeLocalPath("app", "src\\app")
        }
    }

    @Test
    fun `项目级路径不能逃出项目根`() {
        assertThrows(DeploymentConfigException::class.java) {
            requireRelativeLocalPath("app", "../outside")
        }
        assertThrows(DeploymentConfigException::class.java) {
            requireRelativeLocalPath("app", "src\\..\\outside")
        }
    }

    @Test
    fun `远端 deployPath 必须是 POSIX 绝对路径`() {
        assertThrows(DeploymentConfigException::class.java) {
            requireAbsoluteDeployPath("app", "srv/app")
        }
        assertThrows(DeploymentConfigException::class.java) {
            requireAbsoluteDeployPath("app", "C:\\srv\\app")
        }
        assertThrows(DeploymentConfigException::class.java) {
            requireAbsoluteDeployPath("app", "/srv\\app")
        }
        assertThrows(DeploymentConfigException::class.java) {
            requireAbsoluteDeployPath("app", "~/app")
        }
        requireAbsoluteDeployPath("app", "/srv/app")
        requireAbsoluteDeployPath("app", "  /srv/app  ")
        assertThrows(DeploymentConfigException::class.java) {
            requireAbsoluteDeployPath("app", "/")
        }
        assertThrows(DeploymentConfigException::class.java) {
            requireAbsoluteDeployPath("app", "/srv/../etc")
        }
    }

    @Test
    fun `相对路径按项目根解析`() {
        // 断言的期望值也用同一套本机路径 API 生成：这条测的是"相对项目根解析"，
        // 不是分隔符长什么样（本文件的其他用例才是平台无关的校验）。
        assertEquals(
            Path.of("/repo").resolve("references/app").toString(),
            resolveLocalPath("/repo", "references/app"),
        )
        assertEquals(Path.of("/abs/app").toString(), resolveLocalPath("/repo", "/abs/app"))
    }
}
