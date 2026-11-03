package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * 项目 `hosts.json` 的只读访问：server 级配置与 deployment。
 *
 * 进程**不再持久化**：远端 `ps` 才是事实，`command + workingDirectory` 是稳定标识。
 * 这里只负责给 `Remote Process` 读 deployment / docker 命令 / 排除项。
 */
internal object RemoteProcessHosts {

    /** 项目级 hosts.json。 */
    fun file(project: Project): Path {
        val base = project.basePath ?: throw IllegalStateException("项目没有 basePath，无法定位 hosts.json")
        return Path.of(base, ".opencode", "remote-shell", "hosts.json")
    }

    /** 读整个 `hosts` 对象；文件不存在 / 坏掉时返回空对象。 */
    fun readHosts(project: Project): JsonObject {
        val path = file(project)
        if (!Files.exists(path)) return JsonObject(emptyMap())
        return runCatching {
            Json.parseToJsonElement(Files.readString(path)).jsonObject["hosts"] as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
    }

    /** 这个 server 在 hosts.json 里有没有条目。 */
    fun hasServer(project: Project, serverName: String): Boolean =
        readHosts(project)[serverName] is JsonObject

    /** 一条 deployment 的纯数据。 */
    data class Deployment(val name: String, val localPath: String, val deployPath: String, val exclude: List<String>)

    /** 某台机器上配置的 deployment。 */
    fun deployments(project: Project, serverName: String): List<Deployment> {
        val entry = readHosts(project)[serverName] as? JsonObject ?: return emptyList()
        val array = entry["deployments"]?.let { element ->
            runCatching { element as kotlinx.serialization.json.JsonArray }.getOrNull()
        } ?: return emptyList()
        return array.mapNotNull { item ->
            val record = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = record["name"]?.jsonPrimitive?.content.orEmpty()
            val localPath = record["localPath"]?.jsonPrimitive?.content.orEmpty()
            val deployPath = record["deployPath"]?.jsonPrimitive?.content.orEmpty()
            if (name.isEmpty() || localPath.isEmpty() || deployPath.isEmpty()) return@mapNotNull null
            // 项目级 hosts.json 跟着仓库走：只允许相对路径。
            requireRelativeLocalPath(name, localPath)
            val exclude = record["exclude"]?.let { element ->
                runCatching { element as kotlinx.serialization.json.JsonArray }.getOrNull()
            }?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            Deployment(name, localPath, deployPath, exclude)
        }
    }

    /** server 级 docker 命令；默认 `docker`，需要 sudo / 绝对路径时在 hosts.json 里配。 */
    fun serverDockerCommand(project: Project, serverName: String): String {
        val entry = readHosts(project)[serverName] as? JsonObject ?: return "docker"
        return entry["dockerCommand"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "docker"
    }

    /** server 级 exclude（对该机器下所有 deployment 生效）。 */
    fun serverExclude(project: Project, serverName: String): List<String> {
        val entry = readHosts(project)[serverName] as? JsonObject ?: return emptyList()
        val array = entry["exclude"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    }
}
