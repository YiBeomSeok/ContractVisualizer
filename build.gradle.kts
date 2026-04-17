plugins {
    id("org.jetbrains.intellij.platform") version "2.5.0"
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("org.jetbrains.kotlin")
        instrumentationTools()
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "org.bmsk.contractvisualizer"
        name = "Contract Visualizer"
        version = "1.0.0"
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "261.*"
        }
        description = "Visualizes MVI Contract files as Mermaid diagrams in a tool window"
    }
}

kotlin {
    jvmToolchain(17)
}
