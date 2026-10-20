package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * `edit_file` 的平台接线：**唯一**允许碰 `VirtualFile` / `Document` 的地方。
 *
 * ## 为什么写回要分两条路
 *
 * IDE 对**在编辑器里打开过的文件**持有内存副本（`Document`）。它与磁盘的一致性由平台自己维护：
 * 外部改了文件，要等 VFS 刷新才会重读。所以从进程外写文件之后的那段时间里，
 * IDE 看到的是旧内容 —— 而 `reformat_file` 会把旧内容格式化后**存回磁盘**，把外部改动整个冲掉。
 *
 * 这里反过来做：**改的就是 IDE 那一份**。
 *
 * - 文件在编辑器里打开 → 改 `Document`，再让 IDE 自己存盘（`saveDocument`）。内存与磁盘同一份，
 *   用户在编辑器里看到的也是刚写进去的内容。
 * - 文件没打开 → 直接写 `VirtualFile`（VFS 层的写），VFS 当场就知道，**不需要等任何刷新**。
 *
 * 读当前内容时同样以 `Document` 优先：编辑器里未保存的改动才是 IDE 真正会用的那一份，
 * 拿磁盘上的旧内容去算替换位置会算错。
 */
internal object FileEditAccess {

    /** 一次编辑的结果。 */
    data class EditOutcome(
        val path: String,
        val created: Boolean,
        val replacements: Int,
        val writtenThrough: String,
        val lineCount: Int,
        val byteCount: Int,
    )

    /**
     * 编辑失败时带回来的信息。
     *
     * 每种失败**自己说人话**（[agentMessage]）：MCP Server 只在 `isError == false` 时才把结构化内容
     * 送上线，所以错误的关键信息必须落在文案里 —— 失败类型最清楚"下一步该干什么"，文案就归它写。
     */
    sealed class EditFailure(message: String) : Exception(message) {

        /** 给 agent 看的文案：说清哪里不对、下一步怎么办。 */
        abstract fun agentMessage(): String

        /** 目标文件不存在，而调用要求替换既有内容。 */
        class FileNotFound(val path: String) : EditFailure("File not found: $path") {
            override fun agentMessage(): String =
                "File not found: $path. Pass an empty `oldText` to create it, or check the path."
        }

        /** `oldText` 在文件里找不到。 */
        class TextNotFound(val path: String, val lineCount: Int) :
            EditFailure("oldText not found in $path (file has $lineCount lines)") {
            override fun agentMessage(): String =
                "oldText not found in $path (the file has $lineCount lines). " +
                    "oldText must match the file verbatim — read the file and copy the exact text."
        }

        /** `oldText` 出现多次，而调用没有要求全部替换。 */
        class AmbiguousText(val path: String, val occurrences: Int, val lines: List<Int>) :
            EditFailure("oldText occurs $occurrences times in $path") {
            override fun agentMessage(): String =
                "oldText occurs $occurrences times in $path (lines ${lines.joinToString(", ")}). " +
                    "Include more surrounding context to make it unique, or pass replaceAll=true."
        }

        /** 文件已存在，却用"空 oldText = 新建"的写法来了。 */
        class AlreadyExists(val path: String) : EditFailure("File already exists: $path") {
            override fun agentMessage(): String =
                "File already exists: $path. Pass the text to replace as `oldText` — " +
                    "an empty oldText is only for creating a new file."
        }

        /** 路径解析不出来（比如项目根未知）。 */
        class PathNotResolvable(val given: String) : EditFailure("Cannot resolve path: $given") {
            override fun agentMessage(): String =
                "Cannot resolve path: $given. Pass a path relative to the project root, or an absolute path."
        }

        /** 写盘失败。 */
        class WriteFailure(val path: String, val reason: String) :
            EditFailure("Cannot write $path: $reason") {
            override fun agentMessage(): String = "Cannot write $path: $reason"
        }
    }

    /**
     * 把项目相对路径解析成绝对路径。
     *
     * 相对路径以**项目根**为基准（不是进程 cwd —— 那不是 IDE 的语义）；
     * 绝对路径原样使用。
     */
    fun resolve(project: Project, filePath: String): String {
        val normalized = filePath.trim()
        if (normalized.isEmpty()) throw EditFailure.PathNotResolvable(filePath)
        val file = java.io.File(normalized)
        if (file.isAbsolute) return file.path
        val base = project.basePath ?: throw EditFailure.PathNotResolvable(filePath)
        return java.io.File(base, normalized).path
    }

    /**
     * 找文件；找不到时刷新一次 VFS 再找。
     *
     * 刷新是必要的：目标文件可能是**上一次调用**刚创建的，纯 nio 写出去的文件 VFS 未必已经知道
     * （这与 `create_shell_run_configuration` 里那条约束同源）。
     */
    private fun findFile(absolutePath: String): VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        fs.findFileByPath(absolutePath)?.let { return it }
        return fs.refreshAndFindFileByPath(absolutePath)
    }

    /**
     * 执行一次编辑。
     *
     * @param replaceAll `oldText` 出现多次时是否全部替换
     * @throws EditFailure 编辑无法安全完成时
     */
    fun edit(
        project: Project,
        absolutePath: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean,
    ): EditOutcome {
        val file = findFile(absolutePath)

        if (file == null) {
            if (oldText.isNotEmpty()) throw EditFailure.FileNotFound(absolutePath)
            return create(absolutePath, newText)
        }

        if (oldText.isEmpty()) throw EditFailure.AlreadyExists(absolutePath)

        val document = FileDocumentManager.getInstance().getDocument(file)
        val current = document?.text ?: String(file.contentsToByteArray(), StandardCharsets.UTF_8)

        val occurrences = countOccurrences(current, oldText)
        if (occurrences == 0) throw EditFailure.TextNotFound(absolutePath, lineCount(current))

        val replaced = if (replaceAll) {
            current.replace(oldText, newText)
        } else {
            if (occurrences > 1) {
                throw EditFailure.AmbiguousText(absolutePath, occurrences, linesOf(current, oldText))
            }
            current.replaceFirst(oldText, newText)
        }

        val writtenThrough = if (document != null) {
            WriteCommandAction.runWriteCommandAction(project, "Edit File (MCP)", null, {
                document.setText(replaced)
            })
            FileDocumentManager.getInstance().saveDocument(document)
            "document"
        } else {
            writeFileContent(file, replaced)
            "file"
        }

        return EditOutcome(
            path = absolutePath,
            created = false,
            replacements = if (replaceAll) occurrences else 1,
            writtenThrough = writtenThrough,
            lineCount = lineCount(replaced),
            byteCount = replaced.toByteArray(StandardCharsets.UTF_8).size,
        )
    }

    /** 新建文件（父目录一并建），内容就是 `newText`。 */
    private fun create(absolutePath: String, newText: String): EditOutcome {
        val target = java.io.File(absolutePath)
        val parentPath = target.parentFile?.path
            ?: throw EditFailure.PathNotResolvable(absolutePath)
        val bytes = newText.toByteArray(StandardCharsets.UTF_8)
        try {
            WriteAction.runAndWait<IOException> {
                // 父目录也要走 VFS：平台才知道这棵目录树存在，IDE 的文件树才刷新得出来。
                val parent = LocalFileSystem.getInstance().refreshAndFindFileByPath(parentPath)
                    ?: VfsUtil.createDirectoryIfMissing(parentPath)
                    ?: throw IOException("cannot create directory $parentPath")
                val created = parent.createChildData(FileEditAccess, target.name)
                created.setBinaryContent(bytes)
            }
        } catch (e: IOException) {
            throw EditFailure.WriteFailure(absolutePath, e.message ?: e::class.java.simpleName)
        }
        return EditOutcome(
            path = absolutePath,
            created = true,
            replacements = 0,
            writtenThrough = "file",
            lineCount = lineCount(newText),
            byteCount = bytes.size,
        )
    }

    /** 写文件内容（文件没在编辑器里打开时走这条）。 */
    private fun writeFileContent(file: VirtualFile, content: String) {
        try {
            WriteAction.runAndWait<IOException> {
                file.setBinaryContent(content.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: IOException) {
            throw EditFailure.WriteFailure(file.path, e.message ?: e::class.java.simpleName)
        }
    }

    /** 行数（按换行计，末尾无换行也算一行）。 */
    fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    /** 数 `needle` 在 `haystack` 里出现几次（非重叠）。 */
    fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    /** `needle` 出现的行号（从 1 开始），用于"这里不唯一"的提示。 */
    fun linesOf(haystack: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val result = mutableListOf<Int>()
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            result.add(haystack.take(index).count { it == '\n' } + 1)
            index = haystack.indexOf(needle, index + needle.length)
        }
        return result
    }
}
