import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java") // Java 支持
    alias(libs.plugins.kotlin) // Kotlin 支持
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// 设置构建项目使用的 JVM 语言级别
kotlin {
    jvmToolchain(21)
}

// 配置项目依赖
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// 依赖由 Gradle 版本目录管理 - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // 插件依赖。`platformBundledPlugins` 取自 gradle.properties。
        // com.intellij.mcpserver 是 IDE 内置插件（MCP Server），编译 MCP 工具时必须挂上。
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // 来自 JetBrains Marketplace 的插件
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // 来自 IntelliJ Platform 的模块依赖
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)

        // 插件签名器（marketplace-zip-signer）。`signPlugin` 任务需要它，否则报
        // "Please ensure the `zipSigner()` entry is present in the project dependencies section"。
        // 签名所需的证书通过 `intellijPlatform.signing` 的 3 个环境变量传入。
        zipSigner()
    }
}

// 配置 IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // 描述不从这里拼：它直接写在 plugin.xml 的 <description> 里。
        //
        // 原因：<description> 是**面向 Marketplace 的资产**，服务端会校验"前 40 个字符必须是英文"
        // （JetBrains Marketplace listing 文档 + 上传时的硬校验），而 README 是中文文档。
        // 两边共用一份文本只会让"仓库文档语言"和"面向市场的语言"互相打架——现在各归各的：
        // plugin.xml 里一份英文描述，README 里一份中文说明。

        // <change-notes> 与 <description> 同理，是**面向 Marketplace 的英文资产**：从 CHANGELOG.en.md
        // 取当前版本段落渲染。仓库内部的中文变更日志仍是 CHANGELOG.md（由 changelog 插件的
        // patchChangelog 维护）——两个文件各自服务一种读者，谁也不去迁就谁。
        // 缺段落就构建失败：发版时忘了写英文说明会被立刻拦下，而不是上传后收到退回邮件。
        val englishChangelog = providers.fileContents(layout.projectDirectory.file("CHANGELOG.en.md")).asText
        changeNotes = providers.gradleProperty("pluginVersion").zip(englishChangelog) { pluginVersion, markdown ->
            val heading = "## [$pluginVersion]"
            val lines = markdown.lines()
            val start = lines.indexOfFirst { it.startsWith(heading) }
            if (start < 0) {
                throw GradleException("CHANGELOG.en.md 里找不到 $heading 段落：发版前必须先写上英文变更说明。")
            }
            markdownToHTML(lines.drop(start + 1).takeWhile { !it.startsWith("## [") }.joinToString("\n"))
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 插件版本基于 SemVer（https://semver.org），支持 -alpha.1 之类的预发布标签
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// 配置 Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

// 配置 Gradle Kover Plugin - read more: https://kotlin.github.io/kotlinx-kover/gradle-plugin/#configuration-details
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}
