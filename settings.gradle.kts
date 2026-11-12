pluginManagement {
    repositories {
        // 国内镜像优先
        maven("https://maven.aliyun.com/repository/gradle-plugin") // Gradle 插件门户镜像
        maven("https://maven.aliyun.com/repository/public")        // Maven Central 镜像
        maven("https://maven.neoforged.net/releases")              // NeoForge moddev 插件
        maven("https://maven.parchmentmc.org/")                    // Parchment mappings
        // 官方源兜底
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
