package com.shenyuanlaolarou.ideamcpenhance.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `deployPath` → IDE 的「rootFolder + 相对路径」映射（纯函数）。
 *
 * 关键契约：两者拼回来的绝对路径必须**始终等于** `deployPath`，否则同步会把文件
 * 传到别处而界面上看起来一切正常。
 */
class DeploymentMappingTest {

    @Test
    fun `能嵌进现有 rootFolder 时保留 root，只用相对路径`() {
        assertEquals("/srv/app" to "/current", resolveMapping("/srv/app", "/srv/app/current"))
        assertEquals("/srv/app" to "/", resolveMapping("/srv/app", "/srv/app"))
    }

    @Test
    fun `嵌不进现有 rootFolder 时改用 deployPath 作 root`() {
        assertEquals("/srv/other" to "/", resolveMapping("/srv/app", "/srv/other"))
        assertEquals("/srv/app2" to "/", resolveMapping("/srv/app", "/srv/app2"))
    }

    @Test
    fun `没有 rootFolder 时直接用 deployPath`() {
        assertEquals("/srv/app" to "/", resolveMapping(null, "/srv/app"))
        assertEquals("/srv/app" to "/", resolveMapping("   ", "  /srv/app "))
    }
}
