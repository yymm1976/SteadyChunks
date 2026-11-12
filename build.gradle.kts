plugins {
    id("java-library")
    id("maven-publish")
    id("net.neoforged.moddev") version "2.0.139"
    id("idea")
}

val parchment_minecraft_version : String by project
val parchment_mappings_version  : String by project
val minecraft_version           : String by project
val minecraft_version_range     : String by project
val neo_version                 : String by project
val neo_version_range           : String by project
val loader_version_range        : String by project
val mod_id                      : String by project
val mod_name                    : String by project
val mod_license                 : String by project
val mod_version                 : String by project
val mod_group_id                : String by project

tasks.wrapper.configure {
    distributionType = Wrapper.DistributionType.BIN
}

version = mod_version
group = mod_group_id

base {
    // Gradle 会自动在 archivesName 后追加 version，避免重复
    archivesName.set("${mod_id}-${minecraft_version}")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))

neoForge {
    version = neo_version

    parchment {
        mappingsVersion = parchment_mappings_version
        minecraftVersion = parchment_minecraft_version
    }

    runs {
        create("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
        }

        create("server") {
            server()
            gameDirectory = file("run-server")
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
        }

        create("gameTestServer") {
            type = "gameTestServer"
            gameDirectory = file("run-server")
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
        }

        create("data") {
            data()
            gameDirectory = file("run-data")
            programArguments.addAll(
                "--mod",
                mod_id,
                "--all",
                "--output",
                file("src/generated/resources/").absolutePath,
                "--existing",
                file("src/main/resources/").absolutePath
            )
        }

        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        create(mod_id) {
            sourceSet(sourceSets.main.get())
        }
    }
}

the<org.gradle.api.tasks.SourceSetContainer>().getByName("main").resources.srcDir("src/generated/resources")

val localRuntime: Configuration by configurations.creating
configurations {
    named("runtimeClasspath") {
        extendsFrom(localRuntime)
    }
}

repositories {
    // 国内镜像优先
    maven("https://maven.aliyun.com/repository/public") // Maven Central 镜像
    maven("https://maven.neoforged.net/releases")       // NeoForge 本体与依赖
    maven("https://maven.parchmentmc.org/")             // Parchment mappings
    maven("https://repo.spongepowered.org/repository/maven-public/") // Mixin API
    // 官方源兜底
    mavenCentral()
}

dependencies {
    // Gson：报告导出 JSON 格式（NeoForge 已内置，此处声明为 compileOnly 以便 IDE 解析）
    add("compileOnly", "com.google.code.gson:gson:2.10.1")
    // Mixin API：IMixinConfigPlugin 等扩展点需要（NeoForge 运行时内置，编译期需显式声明）
    add("compileOnly", "org.spongepowered:mixin:0.8.5")

    // P2-19：单元测试依赖（JUnit 5 + 平台启动器）
    add("testImplementation", platform("org.junit:junit-bom:5.10.2"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter:5.10.2")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")

    // P2-19：测试运行期需要 SLF4J（被测类引用 SteadyChunks.LOGGER 触发 SLF4J 类加载）
    // NeoForge 运行时内置 SLF4J，但单元测试不启动 NeoForge，需显式提供
    add("testRuntimeOnly", "org.slf4j:slf4j-api:2.0.7")
    add("testRuntimeOnly", "org.slf4j:slf4j-simple:2.0.7")
    // 注：MC/NeoForge 类不放入 testCompileOnly/testRuntimeOnly。
    // 原因：neoforge fat jar 会破坏 Gradle test worker 的 bootstrap classloader，
    // 导致 ClassNotFoundException: GradleWorkerMain。
    // 测试代码仅调用不直接引用 MC 类的方法（如 tryReserve(UUID,long,long)），
    // JVM 懒加载机制保证运行时不需要 MC 类。
}

tasks.named("createMinecraftArtifacts") {
    dependsOn("generateModMetadata")
}

val generateModMetadata = tasks.register<ProcessResources>("generateModMetadata") {
    val replaceProperties = mapOf(
        "minecraft_version"       to minecraft_version,
        "minecraft_version_range" to minecraft_version_range,
        "neo_version"             to neo_version,
        "neo_version_range"       to neo_version_range,
        "loader_version_range"    to loader_version_range,
        "mod_id"                  to mod_id,
        "mod_name"                to mod_name,
        "mod_license"             to mod_license,
        "mod_version"             to mod_version,
    )
    inputs.properties(replaceProperties)

    expand(replaceProperties)
    from("src/main/templates")
    into("build/generated/sources/modMetadata")
}
the<org.gradle.api.tasks.SourceSetContainer>().getByName("main").resources.srcDir(generateModMetadata)

// P2-19：单元测试配置
// 依赖 MC 类的测试（StructureStartIndexTest/FullCommitQueueTest）排除：
// 这两个测试直接引用 ChunkPos 等 MC 类，普通 JVM 单测运行时无 MC 类会 NoClassDefFoundError。
// 应迁入 NeoForge GameTest 运行环境（待 CI 闭环建立后处理）。
sourceSets {
    test {
        java.exclude("com/mochi_753/steadychunks/structure/StructureStartIndexTest.java")
        java.exclude("com/mochi_753/steadychunks/completion/FullCommitQueueTest.java")
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // 中文用户名 + Windows：Gradle 9 的 worker 用 UTF-8 写 argfile，
    // 但 JVM 读 argfile 按 sun.jnu.encoding（Windows 默认 GBK），导致测试类路径乱码
    // （ClassNotFoundException: ...Test）。强制 worker JVM 用 UTF-8 读取。
    jvmArgs("-Dsun.jnu.encoding=UTF-8")
}

