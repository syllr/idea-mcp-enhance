package com.shenyuanlaolarou.ideamcpenhance.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(descriptor.description.contains("timeoutMs"))
        assertTrue(descriptor.inputSchema.propertiesSchema.containsKey("maxChars"))
    }

    @Test
    fun `execute_in_remote_host 的必填参数`() {
        val descriptor = executeInRemoteHostDescriptor
        assertEquals("execute_in_remote_host", descriptor.name)
        assertEquals(setOf("serverName", "command"), descriptor.inputSchema.requiredProperties)
    }

    @Test
    fun `terminal_io 的必填参数`() {
        val descriptor = terminalIoDescriptor
        assertEquals("terminal_io", descriptor.name)
        assertEquals(setOf("target", "tab", "action"), descriptor.inputSchema.requiredProperties)
        assertTrue(descriptor.inputSchema.propertiesSchema.containsKey("maxChars"))
    }

    @Test
    fun `list_terminals 的输入与输出 schema`() {
        val descriptor = listTerminalsDescriptor
        assertEquals("list_terminals", descriptor.name)
        assertEquals(emptySet<String>(), descriptor.inputSchema.requiredProperties)
        assertTrue(descriptor.inputSchema.propertiesSchema.containsKey("includeUnmanaged"))
        assertTrue(descriptor.outputSchema!!.propertiesSchema.containsKey("terminals"))
        assertTrue(descriptor.description.contains("addressable"))
        assertFalse(descriptor.outputSchema!!.propertiesSchema.containsKey("managedCount"))
    }

    @Test
    fun `terminalTabsToJson 用 kind 区分 MCP 与用户 tab`() {
        val mcp = TerminalTabInfo(
            tab = "MCP-1",
            kind = TerminalKind.ProjectSdk,
            addressable = true,
            title = "MCP-1",
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
        val user = mcp.copy(
            tab = null,
            kind = TerminalKind.User,
            addressable = false,
            title = "MCP-1",
        )

        val json = terminalTabsToJson(listOf(mcp, user))
        assertEquals(JsonPrimitive(2), json["total"])
        val tabs = json["terminals"]!!.jsonArray
        assertEquals(JsonPrimitive("MCP-1"), tabs[0].jsonObject["tab"])
        assertEquals(JsonPrimitive("mcp_project_sdk"), tabs[0].jsonObject["kind"])
        assertFalse(tabs[0].jsonObject.containsKey("managed"))
        assertEquals(JsonNull, tabs[1].jsonObject["tab"])
        assertEquals(JsonPrimitive("user"), tabs[1].jsonObject["kind"])
    }

    @Test
    fun `formatTerminalList 明确提示不可寻址`() {
        val user = TerminalTabInfo(
            tab = null,
            kind = TerminalKind.User,
            addressable = false,
            title = "Local",
            status = "running",
            shell = null,
            workingDirectory = null,
            currentDirectory = null,
            serverName = null,
            lastCommand = null,
            lastCommandAt = null,
            lastExitCode = null,
            lastTimedOut = null,
            createdAt = null,
            lastUsedAt = null,
        )

        val text = formatTerminalList(listOf(user))
        assertTrue(text.contains("not addressable"))
        assertTrue(text.contains("terminal_io"))
    }

    @Test
    fun `输出 schema 里可空字段允许 null`() {
        val exitCode = projectSdkTerminalDescriptor.outputSchema!!.propertiesSchema["exitCode"]!!.jsonObject
        val types = exitCode["type"] as JsonArray
        assertTrue(types.contains(JsonPrimitive("integer")))
        assertTrue(types.contains(JsonPrimitive("null")))
    }

    @Test
    fun `terminalTimeout 接受 0（异步）`() {
        val args = JsonObject(mapOf("timeoutMs" to JsonPrimitive(0)))
        assertEquals(0, args.terminalTimeout(10_000))
    }

    @Test
    fun `terminalTimeout 缺省时回落默认值`() {
        assertEquals(10_000, JsonObject(emptyMap()).terminalTimeout(10_000))
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
        assertEquals("cd \"\$HOME\"; exec pwd", buildRemoteCommand("pwd", "~", "zsh", false))
        assertEquals(
            "cd \"\$HOME\"/'my dir'; exec pwd",
            buildRemoteCommand("pwd", "~/my dir", "zsh", false),
        )
        assertEquals("cd '/tmp/a b'; exec pwd", buildRemoteCommand("pwd", "/tmp/a b", "zsh", false))
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
}
