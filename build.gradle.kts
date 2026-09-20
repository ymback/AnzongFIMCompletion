plugins {
    id("java")
    // 移除 version，直接使用 settings.gradle.kts 中配置的版本
    id("org.jetbrains.kotlin.jvm")
    // 移除 version，因为 settings.gradle.kts 的 platform.settings 已经接管了
    id("org.jetbrains.intellij.platform")
}

group = "gov.anzong.fim"
version = "1.0.1"
// 插件配置：版本兼容范围写在这里
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("242")
            untilBuild.set("999.*")
        }
    }
}
dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.1")
        bundledPlugins("com.intellij.java")
        instrumentationTools()
        jetbrainsRuntime("21")
    }

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
}
// 1. 使用官方推荐的 Toolchain，一键同步 Java 和 Kotlin 的编译目标为 21
kotlin {
    jvmToolchain(21)
}

// 2. 仅降级 Kotlin 语法版本到 1.9，完美避开 IDEA 2024.2 自带环境的 SpillingKt 崩溃
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        // 注意：这里不再需要手动设置 jvmTarget，jvmToolchain 已经全权接管
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
}