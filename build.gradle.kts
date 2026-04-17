import org.jetbrains.changelog.Changelog

plugins {
    id("org.jetbrains.intellij.platform") version "2.5.0"
    id("org.jetbrains.changelog") version "2.2.1"
    kotlin("jvm") version "2.0.21"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        bundledPlugin("org.jetbrains.kotlin")
        instrumentationTools()
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup")
        name = providers.gradleProperty("pluginDisplayName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
        description = "Visualizes MVI Contract files as Mermaid diagrams in a tool window"
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(providers.gradleProperty("pluginVersion").get()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

changelog {
    version = providers.gradleProperty("pluginVersion")
    groups.empty()
    repositoryUrl = "https://github.com/YiBeomSeok/ContractVisualizer"
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}
