plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.ming.i18nformat"
version = "1.0.7"

repositories {
    maven("https://maven.aliyun.com/repository/central")
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    // 使用本地已安装的 IDEA 作为编译/调试平台，避免下载 IDE 发行包
    localPath.set("D:/IDEA/IDEA2024/IntelliJ IDEA 2024.2.6")
    // 本地 IDEA 缺少 java-compiler-ant-tasks 时跳过字节码插桩
    instrumentCode.set(false)

    plugins.set(listOf(
        "JavaScript",
        "org.jetbrains.plugins.vue",
    ))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("262.*")
    }

//    signPlugin {
//        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
//        privateKey.set(System.getenv("PRIVATE_KEY"))
//        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
//    }
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
        doFirst {
            println("Skipping signature because no certificate is configured")
        }
    }
}
