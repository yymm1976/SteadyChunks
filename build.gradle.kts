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

sourceSets.main.get().resources {
    srcDir("src/generated/resources")
}

val localRuntime: Configuration by configurations.creating
configurations {
    runtimeClasspath {
        extendsFrom(localRuntime)
    }
}

// P2-19：moddev-gradle 生成的 Minecraft 类 jar（parchment-mapped），供测试源集访问 MC 类
val minecraftArtifact = layout.buildDirectory.file("moddev/artifacts/neoforge-${neo_version}.jar")

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
    compileOnly("com.google.code.gson:gson:2.10.1")
    // Mixin API：IMixinConfigPlugin 等扩展点需要（NeoForge 运行时内置，编译期需显式声明）
    compileOnly("org.spongepowered:mixin:0.8.5")

    // P2-19：单元测试依赖（JUnit 5 + 平台启动器）
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // P2-19：测试编译期需要 MC 类（ChunkPos 等）。仅声明 testCompileOnly，
    // 运行期通过 -Xbootclasspath/a 注入，避免污染 Gradle test worker 的 system classpath
    // （moddev unitTest 扩展在 Gradle 9 下会破坏 worker classpath，故不启用）。
    testCompileOnly(files(minecraftArtifact))

    // P2-19：测试运行期需要 SLF4J（被测类引用 SteadyChunks.LOGGER 触发 SLF4J 类加载）
    // NeoForge 运行时内置 SLF4J，但单元测试不启动 NeoForge，需显式提供
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.7")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.7")
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
sourceSets.main.get().resources.srcDir(generateModMetadata)
neoForge.ideSyncTask(generateModMetadata)

tasks.compileJava {
    options.encoding = "UTF-8"
}

// P2-19：单元测试配置
// 注意：MC jar 不能放入 testRuntimeClasspath（污染 worker），也不能用 -Xbootclasspath/a
// （neoforge fat jar 会破坏 bootstrap classloader，导致 GradleWorkerMain ClassNotFoundException）。
// 因此依赖 MC 类的测试（StructureStartIndexTest/FullCommitQueueTest）运行时会因 NoClassDefFoundError 失败，
// 暂时排除这两个测试，待 moddev-gradle 完整支持 Gradle 9 的 unitTest 后再启用。
tasks.test {
    useJUnitPlatform()
    dependsOn("createMinecraftArtifacts")
    filter {
        excludeTestsMatching("com.mochi_753.steadychunks.structure.StructureStartIndexTest")
        excludeTestsMatching("com.mochi_753.steadychunks.completion.FullCommitQueueTest")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.compileTestJava {
    options.encoding = "UTF-8"
    dependsOn("createMinecraftArtifacts")
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}
