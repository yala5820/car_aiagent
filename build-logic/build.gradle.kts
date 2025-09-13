// 文件路径: build-logic/build.gradle.kts
plugins {
    `kotlin-dsl`
}

// **【错误修正】**
// 显式应用 'java' 插件，并配置 JVM toolchain。
// 这将强制所有编译任务（Java 和 Kotlin）使用统一的 JDK 版本，
// 从而解决因环境不一致导致的 API 解析错误。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    google()
    mavenCentral()
}

// 从主项目的根目录直接读取版本号。
// 使用正确的相对路径来定位文件。
val agpVersion = file("../gradle/libs.versions.toml").readLines()
    .first { it.trim().startsWith("agp =") }
    .substringAfter("=")
    .trim()
    .removeSurrounding("\"")

dependencies {
    // 依赖 Android Gradle Plugin 的 API
    implementation("com.android.tools.build:gradle:$agpVersion")
    // 依赖 ASM 库用于字节码操作
    implementation("org.ow2.asm:asm-commons:9.6")
}

// 注册我们的插件，以便可以在 app 模块中通过 ID 使用它
gradlePlugin {
    plugins {
        register("desugarTransform") {
            id = "com.example.desugar-transform"
            implementationClass = "com.example.gradle.plugin.DesugarTransformPlugin"
        }
    }
}
