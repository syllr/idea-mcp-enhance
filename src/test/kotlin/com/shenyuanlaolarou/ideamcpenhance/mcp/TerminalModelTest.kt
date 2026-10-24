package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端工具的纯函数 / 契约测试：
 * - 文本清洗与截断
 * - 远程命令段切分（marker + 文本 diff）
 * - 工具描述符的必填参数 / 分类 / 输出 schema
 *
 * 不测平台层（真终端 / SSH），那部分只能 `runIde` 手工验证。
 */
class TerminalModelTest {

    // ---------- 文本清洗 ----------

    @Test
    fun `cleanTerminalText 去掉行尾空格和尾部空行`() {
        val (text, truncated) = cleanTerminalText("a   \n\nb  \n\n\n")
        assertEquals("a\n\nb", text)
        assertFalse(truncated)
    }

    @Test
    fun `cleanTerminalText 超长时截断并标记`() {
        val (text, truncated) = cleanTerminalText("abcdef", maxChars = 3)
        assertEquals("… (truncated)\ndef", text)
        assertTrue(truncated)
    }

    @Test
    fun `normalizeTerminalSnapshot 与清洗规则一致`() {
        assertEquals("a\n\nb", normalizeTerminalSnapshot("a  \n\nb  \n\n"))
    }

    // ---------- 文本 diff ----------

    @Test
    fun `textDelta 在 after 以 before 为前缀时返回新增部分`() {
        val (delta, truncated) = textDelta("A", "AB")
        assertEquals("B", delta)
        assertFalse(truncated)
    }

    @Test
    fun `textDelta 在 buffer 被裁剪时返回可用部分并标记`() {
        val (delta, truncated) = textDelta("A", "XB")
        assertEquals("XB", delta)
        assertTrue(truncated)
    }

    // ---------- 提示符 ----------

    @Test
    fun `looksLikePrompt 识别常见提示符`() {
        assertTrue(looksLikePrompt("yutao@host:~$ "))
        assertTrue(looksLikePrompt("root@host:/#   "))
        assertTrue(looksLikePrompt("user@host ~ % "))
        assertFalse(looksLikePrompt("plain output"))
        assertFalse(looksLikePrompt(""))
    }

    // ---------- 命令段 ----------

    @Test
    fun `stripCommandEchoAndPrompt 去掉命令回显和尾提示符`() {
        val delta = "yutao@host:~$ echo A\nA\nyutao@host:~$ "
        val (output, _) = stripCommandEchoAndPrompt(delta, "echo A")
        assertEquals("A", output)
    }

    @Test
    fun `matchPromptCommand 同时识别提示符和命令`() {
        val match = matchPromptCommand("yutao@host:~$ ls")
        assertEquals("yutao@host:~", match?.prompt)
        assertEquals('$', match?.marker)
        assertEquals("ls", match?.command)

        assertEquals(
            "printf \"Continue? \"; read ans",
            matchPromptCommand("yutao@host:~$ printf \"Continue? \"; read ans")?.command,
        )
        assertEquals("ls", matchPromptCommand("[yutao@host ~]$ ls")?.command)
        assertEquals("ls", matchPromptCommand("$ ls")?.command)
        assertEquals("echo \"$ foo\"", matchPromptCommand("yutao@host:~$ echo \"$ foo\"")?.command)
        assertEquals("ls", matchPromptCommand("(venv) yutao@host:~$ ls")?.command)
        assertEquals("ls", matchPromptCommand("yutao@host:/tmp/my dir$ ls")?.command)
        assertEquals("ls", matchPromptCommand("yutao@host ~ % ls")?.command)
        assertEquals("whoami", matchPromptCommand("root@host:/# whoami")?.command)

        assertNull(matchPromptCommand("price: $ 10"))
        assertNull(matchPromptCommand("yutao@host:~$"))
    }

    @Test
    fun `buildRemoteCommandSegments 包含用户手动输入的命令`() {
        val text = """
            yutao@host:~$ echo A
            A
            yutao@host:~$ ls
            file1
            file2
            yutao@host:~$ ls
            file1
            file2
            yutao@host:~$ 
        """.trimIndent()

        val (segments, truncated) = buildRemoteCommandSegments(text, emptyList(), 3)

        assertEquals(3, segments.size)
        assertEquals("echo A", segments[0].command)
        assertEquals("A", segments[0].output)
        assertEquals("ls", segments[1].command)
        assertEquals("file1\nfile2", segments[1].output)
        assertEquals("ls", segments[2].command)
        assertEquals("file1\nfile2", segments[2].output)
        assertNull(segments[0].exitCode)
        assertFalse(truncated)
    }

    @Test
    fun `buildRemoteCommandSegments tail 只取最后 N 条`() {
        val text = """
            yutao@host:~$ echo A
            A
            yutao@host:~$ ls
            file1
            yutao@host:~$ pwd
            /Users/yutao
            yutao@host:~$ 
        """.trimIndent()

        val (segments, _) = buildRemoteCommandSegments(text, emptyList(), 2)

        assertEquals(2, segments.size)
        assertEquals("ls", segments[0].command)
        assertEquals("file1", segments[0].output)
        assertEquals("pwd", segments[1].command)
        assertEquals("/Users/yutao", segments[1].output)
    }

    @Test
    fun `buildRemoteCommandSegments 把包装命令映射回用户命令`() {
        val text = "yutao@host:~$ zsh -lic 'echo A'\nA\nyutao@host:~$ "
        val markers = listOf(TerminalCommandMarker("echo A", "zsh -lic 'echo A'", ""))

        val (segments, _) = buildRemoteCommandSegments(text, markers, 1)

        assertEquals(1, segments.size)
        assertEquals("echo A", segments[0].command)
        assertEquals("A", segments[0].output)
    }

    @Test
    fun `buildRemoteCommandSegments 空输出和失败输出`() {
        val text = """
            yutao@host:~$ echo ""
            
            yutao@host:~$ false
            yutao@host:~$ ls /definitely-not-exist
            ls: /definitely-not-exist: No such file or directory
            yutao@host:~$ 
        """.trimIndent()

        val (segments, _) = buildRemoteCommandSegments(text, emptyList(), 3)

        assertEquals(3, segments.size)
        assertEquals("echo \"\"", segments[0].command)
        assertEquals("", segments[0].output)
        assertEquals("false", segments[1].command)
        assertEquals("", segments[1].output)
        assertEquals("ls /definitely-not-exist", segments[2].command)
        assertTrue(segments[2].output.contains("No such file or directory"))
    }

    @Test
    fun `buildRemoteCommandSegments 保留中文 emoji 等特殊字符`() {
        val text = "yutao@host:~$ printf 'CJK: 中文 🚀\\n'\nCJK: 中文 🚀\nyutao@host:~$ "

        val (segments, _) = buildRemoteCommandSegments(text, emptyList(), 1)

        assertEquals(1, segments.size)
        assertEquals("printf 'CJK: 中文 🚀\\n'", segments[0].command)
        assertEquals("CJK: 中文 🚀", segments[0].output)
    }

    @Test
    fun `buildRemoteCommandSegments 多行命令包含续行`() {
        val text = """
            yutao@host:~$ for i in 1 2; do
            for>  echo ${'$'}i
            for> done
            1
            2
            yutao@host:~$ 
        """.trimIndent()

        val (segments, truncated) = buildRemoteCommandSegments(text, emptyList(), 1)

        assertEquals(1, segments.size)
        assertEquals("for i in 1 2; do\necho ${'$'}i\ndone", segments[0].command)
        assertEquals("1\n2", segments[0].output)
        assertFalse(truncated)
    }

    @Test
    fun `buildRemoteCommandSegments here-doc`() {
        val text = """
            yutao@host:~$ cat <<EOF
            > hello
            > EOF
            hello
            yutao@host:~$ 
        """.trimIndent()

        val (segments, _) = buildRemoteCommandSegments(text, emptyList(), 1)

        assertEquals(1, segments.size)
        assertEquals("cat <<EOF\nhello\nEOF", segments[0].command)
        assertEquals("hello", segments[0].output)
    }

    @Test
    fun `capSegments 保留最近输出`() {
        val segments = listOf(
            TerminalCommandSegment("a", "12345", null),
            TerminalCommandSegment("b", "67890", null),
        )

        val (capped, truncated) = capSegments(segments, 5)

        assertEquals(1, capped.size)
        assertEquals("b", capped[0].command)
        assertEquals("67890", capped[0].output)
        assertTrue(truncated)
    }

    @Test
    fun `buildRemoteCommandSegments 超大输出用最后一个 marker 合成`() {
        val markers = listOf(TerminalCommandMarker("seq 1 50000", "seq 1 50000", "prompt"))
        val text = "44987\n44988\n50000\nyutao@host:~$ "

        val (segments, truncated) = buildRemoteCommandSegments(text, markers, 1, maxChars = 20)

        assertEquals(1, segments.size)
        assertEquals("seq 1 50000", segments[0].command)
        assertTrue(segments[0].output.contains("50000"))
        assertTrue(truncated)
    }

    // ---------- 返回 JSON ----------

    @Test
    fun `TerminalRunResult 的 null 字段保留为 JsonNull`() {
        val json = TerminalRunResult(
            tab = "MCP-1",
            text = "out",
            exitCode = null,
            timedOut = false,
            running = null,
            truncated = false,
        ).toJson()

        assertEquals(JsonNull, json["exitCode"])
        assertEquals(JsonNull, json["running"])
        assertEquals(JsonNull, json["warning"])
        assertEquals(JsonPrimitive("MCP-1"), json["tab"])
    }

    @Test
    fun `TerminalReadResult 的 commands 形状`() {
        val json = TerminalReadResult(
            tab = "MCP-1",
            text = null,
            commands = listOf(TerminalCommandSegment("echo A", "A", null)),
            running = null,
            truncated = false,
        ).toJson()

        assertNull(json["text"])
        val commands = json["commands"]!!.jsonArray
        assertEquals(1, commands.size)
        assertEquals(JsonPrimitive("echo A"), commands[0].jsonObject["command"])
        assertEquals(JsonNull, commands[0].jsonObject["exitCode"])
    }

    // ---------- schema 契约 ----------

    @Test
    fun `project_sdk_terminal 的必填参数与分类`() {
        val descriptor = projectSdkTerminalDescriptor
        assertEquals("project_sdk_terminal", descriptor.name)
        assertEquals("Terminal", descriptor.category.shortName)
        assertEquals(setOf("command"), descriptor.inputSchema.requiredProperties)
        // 超时不再是参数，也不是描述里该出现的东西
        assertFalse(descriptor.description.contains("timeoutMs"))
        assertFalse(descriptor.inputSchema.propertiesSchema.containsKey("timeoutMs"))
        assertTrue(descriptor.inputSchema.propertiesSchema.containsKey("maxChars"))
    }

    @Test
    fun `execute_in_remote_host 的必填参数`() {
        val descriptor = executeInRemoteHostDescriptor
        assertEquals("execute_in_remote_host", descriptor.name)
        assertEquals(setOf("serverName", "command"), descriptor.inputSchema.requiredProperties)
    }

    @Test
    fun `执行工具不再暴露 timeoutMs`() {
        // 超时是工具的固定行为，不是调用方的旋钮：暴露出去只会让人以为结果可控。
        for (descriptor in listOf(projectSdkTerminalDescriptor, executeInRemoteHostDescriptor)) {
            assertFalse(descriptor.name, descriptor.inputSchema.propertiesSchema.containsKey("timeoutMs"))
            assertFalse(descriptor.name, descriptor.description.contains("timeoutMs"))
        }
        assertEquals(300_000, TERMINAL_CALL_TIMEOUT_MS)
    }

    @Test
    fun `两个执行工具都支持指定或新开 tab`() {        // 默认复用：省略 tab 就打进最近用过的空闲 tab；新 shell 是显式选择。
        for (descriptor in listOf(projectSdkTerminalDescriptor, executeInRemoteHostDescriptor)) {
            val properties = descriptor.inputSchema.propertiesSchema
            assertTrue(descriptor.name, properties.containsKey("tab"))
            assertTrue(descriptor.name, properties.containsKey("newTab"))
            assertFalse(descriptor.name, descriptor.inputSchema.requiredProperties.contains("tab"))
            assertFalse(descriptor.name, descriptor.inputSchema.requiredProperties.contains("newTab"))
        }
    }

    @Test
    fun `每种来源各有一条默认 tab`() {
        val registry = TerminalSessionRegistry()
        registry.put("MCP-1", TerminalSessionRegistry.State(kind = TerminalKind.ProjectSdk))
        registry.put("mcp-mbp-1", remoteState("mbp", 100))
        registry.put("mcp-gpu-1", remoteState("gpu", 100))
        registry.registerDefault("MCP-1", TerminalKind.ProjectSdk)
        registry.registerDefault("mcp-mbp-1", TerminalKind.Remote, "mbp")
        registry.registerDefault("mcp-gpu-1", TerminalKind.Remote, "gpu")

        assertEquals("MCP-1", registry.defaultTab(TerminalKind.ProjectSdk))
        assertEquals("mcp-mbp-1", registry.defaultTab(TerminalKind.Remote, "mbp"))
        assertEquals("mcp-gpu-1", registry.defaultTab(TerminalKind.Remote, "gpu"))
        // 没连过的服务器没有默认 —— 调用方据此新开一条
        assertNull(registry.defaultTab(TerminalKind.Remote, "other"))
    }

    @Test
    fun `默认 tab 只有一条，关掉后才会重建`() {
        val registry = TerminalSessionRegistry()
        registry.put("MCP-1", TerminalSessionRegistry.State(kind = TerminalKind.ProjectSdk))
        registry.registerDefault("MCP-1", TerminalKind.ProjectSdk)
        // newTab 建的第二天条不抢默认身份
        registry.put("MCP-2", TerminalSessionRegistry.State(kind = TerminalKind.ProjectSdk))
        registry.registerDefault("MCP-2", TerminalKind.ProjectSdk)
        assertEquals("MCP-1", registry.defaultTab(TerminalKind.ProjectSdk))

        // 默认 tab 被关掉：没有默认了，下次调用重新建一条
        registry.remove("MCP-1")
        assertNull(registry.defaultTab(TerminalKind.ProjectSdk))

        registry.registerDefault("MCP-2", TerminalKind.ProjectSdk)
        assertEquals("MCP-2", registry.defaultTab(TerminalKind.ProjectSdk))
    }

    @Test
    fun `远程 tab 名带服务器名`() {
        assertEquals("mcp-mbpmiddleware", remoteTabPrefix("mbpmiddleware"))
        assertEquals("mcp-gpu-01", remoteTabPrefix(" gpu 01 "))
        assertEquals("mcp-remote", remoteTabPrefix(""))
    }

    @Test
    fun `复用远程 tab 时不套登录 shell、不用 exec`() {
        // 套壳会让 cd / export 留在子 shell 里，exec 会把 tab 的 shell 换掉 —— 两者都会毁掉复用。
        assertEquals("docker ps", buildRemoteCommandInTab("docker ps", null))
        assertEquals("cd '/data/x' && docker ps", buildRemoteCommandInTab("docker ps", "/data/x"))
        assertFalse(buildRemoteCommandInTab("docker ps", "/data/x").contains("exec "))
        assertFalse(buildRemoteCommandInTab("docker ps", "/data/x").contains("-lic"))
    }

    @Test
    fun `terminal_read 只读，terminal_interrupt 只发打断`() {
        val read = terminalReadDescriptor
        assertEquals("terminal_read", read.name)
        assertEquals(setOf("target", "tab"), read.inputSchema.requiredProperties)
        assertTrue(read.inputSchema.propertiesSchema.containsKey("maxChars"))
        // 读不应该带任何"写"的入参
        assertFalse(read.inputSchema.propertiesSchema.containsKey("text"))
        assertFalse(read.inputSchema.propertiesSchema.containsKey("action"))

        val interrupt = terminalInterruptDescriptor
        assertEquals("terminal_interrupt", interrupt.name)
        assertEquals(setOf("target", "tab"), interrupt.inputSchema.requiredProperties)
        // 打断不接受文本 —— 它不能变成隐藏的执行通道
        assertFalse(interrupt.inputSchema.propertiesSchema.containsKey("text"))
        assertFalse(interrupt.inputSchema.propertiesSchema.containsKey("command"))
    }

    @Test
    fun `list_terminals 的输入与输出 schema`() {
        val descriptor = listTerminalsDescriptor
        assertEquals("list_terminals", descriptor.name)
        assertEquals(emptySet<String>(), descriptor.inputSchema.requiredProperties)
        // 用户手开的 tab 不再出现在结果里，参数也一并删掉
        assertFalse(descriptor.inputSchema.propertiesSchema.containsKey("includeUnmanaged"))
        assertTrue(descriptor.outputSchema!!.propertiesSchema.containsKey("sources"))
        assertTrue(descriptor.description.contains("default"))
        assertFalse(descriptor.outputSchema!!.propertiesSchema.containsKey("total"))
    }

    @Test
    fun `terminalGroupsToJson 按来源分组并给出默认`() {
        val mcp = TerminalTabInfo(
            tab = "MCP-1",
            kind = TerminalKind.ProjectSdk,
            addressable = true,
            status = "idle",
            shell = "zsh",
            workingDirectory = "/project",
            currentDirectory = "/project",
            serverName = null,
            lastCommand = "echo ok",
            lastCommandAt = 1L,
            lastExitCode = 0,
            lastTimedOut = false,
            createdAt = 10L,
            lastUsedAt = 20L,
        )
        val remote = mcp.copy(
            tab = "mcp-mbp-1",
            kind = TerminalKind.Remote,
            serverName = "mbp",
        )

        val json = terminalGroupsToJson(
            listOf(
                TerminalSourceGroup(TerminalKind.ProjectSdk, null, "MCP-1", listOf(mcp)),
                // 另一台服务器也还没建过 tab：default 为 null，tabs 为空
                TerminalSourceGroup(TerminalKind.Remote, "mbp", "mcp-mbp-1", listOf(remote)),
            ),
        )
        val sources = json["sources"]!!.jsonArray
        assertEquals(2, sources.size)
        assertEquals(JsonPrimitive("mcp_project_sdk"), sources[0].jsonObject["source"])
        assertEquals(JsonPrimitive("MCP-1"), sources[0].jsonObject["default"])
        assertEquals(1, sources[0].jsonObject["tabs"]!!.jsonArray.size)
        // 远程按服务器分组，默认也是组级的
        assertEquals(JsonPrimitive("mcp_remote"), sources[1].jsonObject["source"])
        assertEquals(JsonPrimitive("mbp"), sources[1].jsonObject["server"])
        assertEquals(JsonPrimitive("mcp-mbp-1"), sources[1].jsonObject["default"])
    }

    @Test
    fun `formatTerminalList 按来源分组并标出默认`() {
        val managed = TerminalTabInfo(
            tab = "MCP-2",
            kind = TerminalKind.ProjectSdk,
            addressable = true,
            status = "idle",
            shell = "zsh",
            workingDirectory = "/project",
            currentDirectory = "/project",
            serverName = null,
            lastCommand = "echo ok",
            lastCommandAt = 1L,
            lastExitCode = 0,
            lastTimedOut = false,
            createdAt = 10L,
            lastUsedAt = 20L,
        )

        val text = formatTerminalList(
            listOf(
                TerminalSourceGroup(TerminalKind.ProjectSdk, null, "MCP-2", listOf(managed)),
                // 还没建过 tab 的服务器：明确说"没有默认"，而不是空一块
                TerminalSourceGroup(TerminalKind.Remote, "mbp", null, emptyList()),
            ),
        )
        // 组标题直接说默认是哪条，而不是在每条 tab 上挂一个布尔
        assertTrue(text.contains("MCP Project SDK — default: MCP-2"))
        assertTrue(text.contains("  MCP-2"))
        assertTrue(text.contains("Remote host mbp — no default yet"))
    }

    @Test
    fun `输出 schema 里可空字段允许 null`() {
        val exitCode = projectSdkTerminalDescriptor.outputSchema!!.propertiesSchema["exitCode"]!!.jsonObject
        val types = exitCode["type"] as JsonArray
        assertTrue(types.contains(JsonPrimitive("integer")))
        assertTrue(types.contains(JsonPrimitive("null")))
    }

    @Test
    fun `terminal_read 不接受 timeout`() {
        // 读是"立即返回"，时序由调用方自己掌握（隔几秒再读一次），不需要参数。
        assertFalse(terminalReadDescriptor.inputSchema.propertiesSchema.containsKey("timeoutMs"))
    }

    @Test
    fun `预检失败 JSON 带 code、current、fix`() {
        val json = TerminalPreflight.Blocked(
            code = "terminal_engine_not_reworked",
            message = "msg",
            current = "terminalEngine=CLASSIC",
            fix = listOf("Settings | Tools | Terminal"),
        ).toJson()

        assertEquals(JsonPrimitive("terminal_engine_not_reworked"), json["code"])
        assertEquals(JsonPrimitive("terminalEngine=CLASSIC"), json["current"])
        assertEquals(1, json["fix"]!!.jsonArray.size)
    }

    // ---------- 工作目录 / 远程命令包装 ----------

    @Test
    fun `shellChangeWarning 识别 exec`() {
        assertTrue(shellChangeWarning("exec bash").isNullOrEmpty().not())
        assertTrue(shellChangeWarning("echo hi; exec zsh").isNullOrEmpty().not())
        assertTrue(shellChangeWarning("echo exec").isNullOrEmpty())
    }

    @Test
    fun `isCmdShell 识别 cmd 和 cmd_dot_exe`() {
        assertTrue(isCmdShell(listOf("cmd.exe")))
        assertTrue(isCmdShell(listOf("C:\\Windows\\System32\\cmd.exe")))
        assertFalse(isCmdShell(listOf("powershell.exe")))
        assertFalse(isCmdShell(listOf("C:\\Program Files\\Git\\bin\\bash.exe")))
    }

    @Test
    fun `expandHomeDirectory 展开 ~`() {
        assertEquals("/home/u", expandHomeDirectory("~", "/home/u"))
        assertEquals("/home/u/a b", expandHomeDirectory("~/a b", "/home/u"))
        assertEquals("C:\\Users\\u\\a b", expandHomeDirectory("~/a b", "C:\\Users\\u", '\\'))
        assertEquals("/tmp", expandHomeDirectory("/tmp", "/home/u"))
        assertNull(expandHomeDirectory(null, "/home/u"))
    }

    @Test
    fun `buildRemoteCommand 处理 ~ 和带空格的路径`() {
        // 不用 `exec`：loginShell=false 时它会把 tab 的交互 shell 换掉，命令结束 tab 就没了。
        assertEquals("cd \"\$HOME\" && pwd", buildRemoteCommand("pwd", "~", "zsh", false))
        assertEquals(
            "cd \"\$HOME\"/'my dir' && pwd",
            buildRemoteCommand("pwd", "~/my dir", "zsh", false),
        )
        assertEquals("cd '/tmp/a b' && pwd", buildRemoteCommand("pwd", "/tmp/a b", "zsh", false))
        assertTrue(buildRemoteCommand("pwd", null, "zsh", true).startsWith("zsh -lic "))
        assertTrue(buildRemoteCommand("pwd", null, "bash", true).startsWith("bash -lc "))
    }

    @Test
    fun `buildRemoteCommandSegments reset 后只剩提示符不合成旧命令`() {
        val markers = listOf(TerminalCommandMarker("echo READY", "echo READY", "old prompt"))
        val text = "yutao@host:~$ "

        val (segments, truncated) = buildRemoteCommandSegments(text, markers, 3)

        assertTrue(segments.isEmpty())
        assertTrue(truncated)
    }

    @Test
    fun `buildRemoteCommandSegments clear 后没有命令段时仍标记 truncated`() {
        val markers = listOf(TerminalCommandMarker("echo X", "echo X", "old prompt"))
        val text = "AFTER_CLEAR\nyutao@host:~$ "

        val (segments, truncated) = buildRemoteCommandSegments(text, markers, 3)

        assertEquals(1, segments.size)
        assertEquals("echo X", segments[0].command)
        assertEquals("AFTER_CLEAR", segments[0].output)
        assertTrue(truncated)
    }

    @Test
    fun `忙等待按 250 500 然后每次 1000 退避`() {
        // 前两次短、之后稳定 1000ms：假忙（约 200ms）第一次就过，真忙也不打满 CPU。
        assertEquals(250L, retryBackoffMs(0))
        assertEquals(500L, retryBackoffMs(1))
        assertEquals(1_000L, retryBackoffMs(2))
        assertEquals(1_000L, retryBackoffMs(99))
    }

    private fun remoteState(serverName: String, lastUsedAt: Long) = TerminalSessionRegistry.State(
        kind = TerminalKind.Remote,
        serverName = serverName,
        lastUsedAt = lastUsedAt,
    )
}
