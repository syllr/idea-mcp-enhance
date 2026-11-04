package com.shenyuanlaolarou.ideamcpenhance.mcp

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ssh.config.unified.SshConfigManager
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.Timer
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * `Remote Process`：在某个 SSH 配置对应的机器上启动 / 停止一个后台进程。
 *
 * 配置存在 run configuration 里；进程状态存在项目 `hosts.json` 的
 * `hosts.<server>.processes` 下 —— 与 OpenCode 的 `remote_start` / `remote_list_processes`
 * 读的是同一份，谁启动的都能在 Run 窗口和工具结果里看到。
 *
 * Start / Rerun 会先做一次 IDE deployment 同步（差量、overwrite），再 `nohup` 启动；
 * Stop 先 TERM 再 KILL。Attach / Unattach 不在这里 —— 它们不启动进程，走 MCP 侧。
 */
class RemoteProcessConfigurationType : ConfigurationTypeBase(
    "RemoteProcess",
    "Remote Process",
    "Run a command on a remote SSH server and track its PID.",
    AllIcons.RunConfigurations.Application,
) {
    init {
        addFactory(RemoteProcessConfigurationFactory(this))
        // 诊断：确认 EP 真的实例化了这个 type（沙箱里排查 "typeKnown=false" 用）。
        com.intellij.openapi.diagnostic.Logger.getInstance(RemoteProcessConfigurationType::class.java)
            .info("RemoteProcess configuration type registered, id=$id")
    }
}

private class RemoteProcessConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    /** 必须是稳定的、非本地化的 id；平台的 default 实现已废弃。 */
    override fun getId(): String = "RemoteProcess"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        RemoteProcessRunConfiguration(project, this)
}

/** run configuration 的持久化字段。 */
class RemoteProcessOptions : RunConfigurationOptions() {
    var server: String? by string()
    var deployment: String? by string()
    var command: String? by string()
    var workingDirectory: String? by string()
    /** 应用自己的日志目录（log4j / logback 等），仅用于查询。process 相对 working directory，docker 是容器内路径。 */
    var logDir: String? by string()
    /** process / docker；空 = 从 command 推断。 */
    var kind: String? by string()
    var container: String? by string()
    /** 这一次启动是 Deploy：Stop 只关日志，不 kill 远端。启动后即清掉，不持久化。 */
    var deployOnly: Boolean by property(false)

    /** MCP 触发的 Deploy：已在运行时直接替换，不弹确认框。一次性标记，读到就清。 */
    @Transient
    var autoReplace: Boolean = false

}

/** 一条 `Remote Process` 配置。 */
class RemoteProcessRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
) : RunConfigurationBase<RemoteProcessOptions>(project, factory, "Remote Process") {

    /** 框架用它实例化 options；不 override 这里，`getOptions()` 会拿到基类实例。 */
    override fun getOptionsClass(): Class<out RunConfigurationOptions> = RemoteProcessOptions::class.java

    val remoteOptions: RemoteProcessOptions
        get() = super.getOptions() as RemoteProcessOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        RemoteProcessSettingsEditor(project)

    override fun checkConfiguration() {
        val server = remoteOptions.server?.takeIf { it.isNotBlank() }
            ?: throw RuntimeConfigurationError("没有选择 SSH 配置。")
        if (RemoteSshClient.sshConfig(project, server) == null) {
            throw RuntimeConfigurationError("IDE 的 SSH 配置里没有 \"$server\"。")
        }
        if (!RemoteProcessHosts.hasServer(project, server)) {
            throw RuntimeConfigurationError("服务器 \"$server\" 不在 hosts.json 里，先执行 /remote-config-sync。")
        }
        if (remoteOptions.command.isNullOrBlank()) {
            throw RuntimeConfigurationError("command 不能为空。")
        }
        val kind = remoteOptions.kind?.takeIf { it.isNotBlank() }
            ?: inferKind(remoteOptions.command.orEmpty())
        if (kind != "process" && kind != "docker") {
            throw RuntimeConfigurationError("kind 只能是 process 或 docker。")
        }
        // docker 不需要同步文件；process 必须有 deployment 才能上传。
        if (kind != "docker" && remoteOptions.deployment.isNullOrBlank()) {
            throw RuntimeConfigurationError("Deployment 不能为空（启动前要先同步它）。")
        }
        if (kind == "docker" && remoteOptions.container.isNullOrBlank()) {
            throw RuntimeConfigurationError("docker 需要 Container 名（用于判断容器是否已启动）。")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        RemoteProcessRunProfileState(this, environment)
}

/** 配置界面：server / deployment 下拉，command / 目录 / 日志文本框，以及该机器的进程列表。 */
private class RemoteProcessSettingsEditor(private val project: Project) :
    SettingsEditor<RemoteProcessRunConfiguration>() {

    private val serverCombo = ComboBox<String>()
    private val deploymentCombo = ComboBox<String>()
    private val commandField = JBTextField()
    private val workingDirectoryField = JBTextField()
    private val logDirField = JBTextField()
    private val kindCombo = ComboBox<String>().apply {
        addItem("process")
        addItem("docker")
    }
    private val containerField = JBTextField()

    private val statusLabel = JBLabel("未查询")
    private val deployButton = JButton("Deploy").apply { addActionListener { deploy() } }
    private val statusPanel = JPanel(BorderLayout(8, 0)).apply {
        add(statusLabel, BorderLayout.CENTER)
        add(deployButton, BorderLayout.EAST)
    }
    /** 配置页打开期间每 10 秒查一次远端状态；关闭后停掉。 */
    private val statusTimer = Timer(10_000) { refreshProcesses() }

    /** 一次查询还没回来就不再发新的，避免状态一直停在「查询中」。 */
    @Volatile
    private var querying = false

    /** 当前编辑的 run configuration 名，用来在列表里默认选中它自己的进程。 */
    private var currentConfiguration: RemoteProcessRunConfiguration? = null

    private lateinit var workingDirectoryRow: Row
    private lateinit var logDirRow: Row
    private lateinit var logDirHintRow: Row
    private lateinit var containerRow: Row

    private val rootPanel: JComponent = panel {
        row("SSH configuration:") { cell(serverCombo).align(AlignX.FILL) }
        row("Deployment:") { cell(deploymentCombo).align(AlignX.FILL) }
        row("Command:") { cell(commandField).align(AlignX.FILL) }
        workingDirectoryRow = row("Working directory:") { cell(workingDirectoryField).align(AlignX.FILL) }
        logDirRow = row("Application log directory:") { cell(logDirField).align(AlignX.FILL) }
        logDirHintRow = row {
            cell(
                JBLabel(
                    "<html>应用自己写日志的目录（如 log4j 的 logs/），仅用于查询，不影响启动。<br>" +
                        "process 填远端宿主机路径；docker 填容器内路径。</html>",
                ).apply { foreground = com.intellij.ui.JBColor.GRAY },
            ).align(AlignX.FILL)
        }
        row("Kind:") { cell(kindCombo).align(AlignX.FILL) }
        containerRow = row("Container:") { cell(containerField).align(AlignX.FILL) }
        separator()
        row { cell(statusPanel).align(AlignX.FILL) }
    }

    init {
        serverCombo.addActionListener {
            refreshDeployments()
            refreshProcesses()
        }
        deploymentCombo.addActionListener { applyDeploymentDefaultPath() }
        kindCombo.addActionListener { updateKindRows() }
        refreshServers()
        updateKindRows()
    }

    /** 按 Kind 显示对应的字段：process 不显示容器项，docker 不显示工作目录项。 */
    private fun updateKindRows() {
        val kind = kindCombo.selectedItem as? String ?: "process"
        workingDirectoryRow.visible(kind == "process")
        logDirRow.visible(true)
        logDirHintRow.visible(true)
        containerRow.visible(kind == "docker")
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    private fun refreshServers() {
        val names = runCatching { SshConfigManager.getInstance(project).configs.map { it.name } }
            .getOrDefault(emptyList())
        val current = serverCombo.selectedItem as? String
        serverCombo.removeAllItems()
        names.forEach { serverCombo.addItem(it) }
        if (current != null && names.contains(current)) serverCombo.selectedItem = current
        refreshDeployments()
        refreshProcesses()
    }

    private fun refreshDeployments() {
        val server = serverCombo.selectedItem as? String
        val names = server?.let { name -> RemoteProcessHosts.deployments(project, name).map { it.name } }
            .orEmpty()
        val current = deploymentCombo.selectedItem as? String
        deploymentCombo.removeAllItems()
        names.forEach { deploymentCombo.addItem(it) }
        if (current != null && names.contains(current)) deploymentCombo.selectedItem = current
        applyDeploymentDefaultPath()
    }

    /**
     * 选中的 deployment 的 `deployPath` 作为 Working directory 的默认值。
     *
     * 规则：当前为空、或还停在上一个 deployment 的默认值 → 跟着换；用户手改过 → 不覆盖。
     */
    private fun applyDeploymentDefaultPath() {
        val server = serverCombo.selectedItem as? String
        val name = deploymentCombo.selectedItem as? String
        val path = if (server != null && name != null) {
            RemoteProcessHosts.deployments(project, server).firstOrNull { it.name == name }?.deployPath
        } else {
            null
        }
        val current = workingDirectoryField.text.trim()
        if (path != null && (current.isEmpty() || current == lastDeploymentPath)) {
            workingDirectoryField.text = path
        }
        lastDeploymentPath = path
    }

    /** 上一次自动填入的 deployment 路径；用户改过 working directory 后就不再覆盖。 */
    private var lastDeploymentPath: String? = null

    /**
     * 查询当前配置对应的远端状态，更新到状态标签。
     *
     * 按 kind 分支：process 用 command + cwd，docker 用容器名。
     */
    private fun refreshProcesses() {
        if (querying) return
        val server = serverCombo.selectedItem as? String ?: return
        val kind = kindCombo.selectedItem as? String ?: "process"
        val docker = RemoteProcessHosts.serverDockerCommand(project, server)

        val probe: () -> Boolean = when (kind) {
            "docker" -> {
                val container = containerField.text.trim()
                if (container.isEmpty()) {
                    statusLabel.text = "未配置 Container"
                    return
                }
                ({
                    RemoteSshClient.run(project, server, "$docker ps -q -f name=^$container$")
                        .trim().isNotEmpty()
                })
            }

            else -> {
                val command = commandField.text.trim()
                val workingDirectory = workingDirectoryField.text.trim().ifBlank { null }
                if (command.isEmpty()) {
                    statusLabel.text = "未配置 command"
                    return
                }
                ({ RemoteProcessFinder.find(project, server, command, workingDirectory) != null })
            }
        }

        querying = true
        if (statusLabel.text.isEmpty() || statusLabel.text == "未查询") statusLabel.text = "查询中..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val running = runCatching { probe() }.getOrDefault(false)
            ApplicationManager.getApplication().invokeLater {
                statusLabel.text = if (running) "running" else "stopped"
                querying = false
            }
        }
    }

    /** 点击 Deploy：应用当前编辑内容，然后走 Run 那条链路启动并打开 Run 窗口看日志。 */
    private fun deploy() {
        val configuration = currentConfiguration ?: run {
            Messages.showErrorDialog(project, "没有绑定到配置实例，重新打开这个配置页再试。", "Remote Process")
            return
        }
        try {
            applyEditorTo(configuration)
        } catch (e: ConfigurationException) {
            Messages.showErrorDialog(project, e.message ?: "配置不完整", "Remote Process")
            return
        }
        val runManager = RunManager.getInstance(project)
        val settings = runManager.findConfigurationByName(configuration.name) ?: run {
            Messages.showErrorDialog(project, "找不到配置 \"${configuration.name}\"。", "Remote Process")
            return
        }
        // Deploy 是发布语义：Stop 只关日志，不 kill 远端。
        // 必须写到 `settings.configuration` —— 这才是 ProgramRunnerUtil 实际启动的实例。
        (settings.configuration as? RemoteProcessRunConfiguration)?.remoteOptions?.deployOnly = true
        runManager.selectedConfiguration = settings

        // 配置对话框是 modal 的：直接调 ProgramRunnerUtil 不会启动，要先关掉对话框。
        val dialog = com.intellij.openapi.ui.DialogWrapper.findInstance(rootPanel)
        val launch = {
            runManager.selectedConfiguration = settings
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }
        if (dialog != null) {
            dialog.close(com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE)
            ApplicationManager.getApplication().invokeLater(launch)
        } else {
            launch()
        }
    }

    override fun createEditor(): JComponent = rootPanel

    override fun resetEditorFrom(configuration: RemoteProcessRunConfiguration) {
        currentConfiguration = configuration
        val options = configuration.remoteOptions
        if (options.server != null) serverCombo.selectedItem = options.server
        refreshDeployments()
        if (options.deployment != null) deploymentCombo.selectedItem = options.deployment
        commandField.text = options.command.orEmpty()
        workingDirectoryField.text = options.workingDirectory.orEmpty()
        logDirField.text = options.logDir.orEmpty()
        kindCombo.selectedItem = options.kind ?: inferKind(options.command.orEmpty())
        containerField.text = options.container.orEmpty()
        lastDeploymentPath = (deploymentCombo.selectedItem as? String)?.takeIf { it.isNotBlank() }?.let { name ->
            val server = serverCombo.selectedItem as? String
            server?.let { RemoteProcessHosts.deployments(project, it).firstOrNull { it.name == name }?.deployPath }
        }
        updateKindRows()
        refreshProcesses()

        // 配置页显示期间每 3 秒查一次；从界面上移除就停掉。
        rootPanel.addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(event: javax.swing.event.AncestorEvent) {
                statusTimer.start()
            }

            override fun ancestorRemoved(event: javax.swing.event.AncestorEvent) {
                statusTimer.stop()
            }

            override fun ancestorMoved(event: javax.swing.event.AncestorEvent) = Unit
        })
    }

    override fun applyEditorTo(configuration: RemoteProcessRunConfiguration) {
        val options = configuration.remoteOptions
        options.server = serverCombo.selectedItem as? String
        options.deployment = deploymentCombo.selectedItem as? String
        options.command = commandField.text.trim()
        options.workingDirectory = workingDirectoryField.text.trim()
        options.logDir = logDirField.text.trim()
        options.kind = kindCombo.selectedItem as? String ?: "process"
        options.container = containerField.text.trim()

        // 保存前校验：必填项为空时 IDE 会拦住 Apply / OK。
        if (options.server.isNullOrBlank()) throw ConfigurationException("SSH configuration 不能为空。", "")
        if (options.kind != "docker" && options.deployment.isNullOrBlank()) {
            throw ConfigurationException("Deployment 不能为空。", "")
        }
        if (options.command.isNullOrBlank()) throw ConfigurationException("Command 不能为空。", "")
        if (options.kind == "docker" && options.container.isNullOrBlank()) {
            throw ConfigurationException("docker 需要 Container 名。", "")
        }
    }
}
