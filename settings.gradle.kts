import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "AnzongFIMCompletion"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.1.0"
}


dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()
        gradlePluginPortal()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
