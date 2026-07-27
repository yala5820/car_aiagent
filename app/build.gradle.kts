import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.objectbox)
}

val localProps = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use {
        props.load(it)
    }
}

android {
    namespace = "com.hirain.aiagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hirain.aiagent"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                /**
                 * CLI 与 Android 必须消费同一份 UID/Meta Model；这里指向唯一规范源，
                 * 禁止 app 目录生成第二份可提交的 default.json。
                 */
                arguments["objectbox.modelPath"] = rootProject.file("rag-schema/objectbox-models/default.json").absolutePath
                arguments["objectbox.myObjectBoxPackage"] = "com.hirain.aiagent.rag.store"
            }
        }

        buildConfigField("String", "DASHSCOPE_API_KEY", "\"${localProps.getProperty("dashscope.api_key", "")}\"")
        buildConfigField("String", "WEATHER_API_KEY", "\"${localProps.getProperty("weather.api_key", "")}\"")
    }

    signingConfigs {
        val debugKeyFile = file(projectDir.toString() + "/../platform.jks")
        if (debugKeyFile.exists()) {
            getByName("debug") {
                storeFile = debugKeyFile
                storePassword = localProps.getProperty("signing.storePassword", "")
                keyAlias = localProps.getProperty("signing.keyAlias", "")
                keyPassword = localProps.getProperty("signing.keyPassword", "")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            if (signingConfigs.findByName("debug") != null) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }

    sourceSets {
        getByName("main").java.srcDir(rootProject.file("rag-schema/src/main/java"))
        /** 测试仅消费离线端生成的 Fixture，禁止在 src/androidTest/assets 维护副本。 */
        getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/ragTestAssets"))
    }

    androidResources {
        /**
         * ObjectBox 预构建库必须以原始 data.mdb 字节进入 APK；压缩会破坏运行时
         * 直接复制与 Hash 交付假设。正式 Asset 后续仍仅允许 data.mdb/manifest.json。
         */
        noCompress += "mdb"
    }
}

val sharedObjectBoxModel = rootProject.file("rag-schema/objectbox-models/default.json")
val sharedObjectBoxModelHash = rootProject.file("rag-schema/objectbox-models/default.sha256")

val verifyRagSharedSchema by tasks.registering {
    group = "verification"
    description = "验证 Android 消费的共享 ObjectBox Meta Model 未被删除或未经审阅地漂移。"
    inputs.files(sharedObjectBoxModel, sharedObjectBoxModelHash)
    doLast {
        check(sharedObjectBoxModel.isFile) { "缺少共享 ObjectBox Meta Model，禁止 Android 自动重新生成 UID。" }
        check(sharedObjectBoxModelHash.isFile) { "缺少共享 Schema Hash 基线。" }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(sharedObjectBoxModel.readBytes())
            .joinToString("") { "%02x".format(it) }
        val expected = sharedObjectBoxModelHash.readText().trim()
        check(actual == expected) {
            "共享 ObjectBox Meta Model Hash 不匹配；Entity/UID 变更必须由 rag-schema 负责人更新 Schema、Fixture 与兼容 Gate。"
        }
    }
}

val prepareRagTestAssets by tasks.registering(Sync::class) {
    group = "verification"
    description = "将离线端 Fixture 与 G404 开发候选复制到 Android generated androidTest assets。"
    from(rootProject.file("rag-schema/test-fixtures/objectbox-v1")) {
        include("data.mdb", "manifest.json")
        into("rag/knowledge_db")
    }
    from(rootProject.file("rag-schema/test-fixtures/development-v1/candidate-v1")) {
        include("data.mdb", "manifest.json")
        into("rag/development_candidate")
    }
      /**
       * Parent-Child V2 是本机生成的 TEST_ONLY 真实资料候选，只允许进入 androidTest APK 做安装和检索验收。
       * 该候选不进入 main assets；在未生成候选的干净工作区中保持为空，设备测试会明确跳过而非伪造资产。
       */
      from(rootProject.file("tools/rag-indexer/trial-output/model-y-2026-refresh-title-v2")) {
          include("data.mdb", "manifest.json")
          into("rag/model_y_title_v2_candidate")
      }
    into(layout.buildDirectory.dir("generated/ragTestAssets"))
}

tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach {
    dependsOn(prepareRagTestAssets)
}

/**
 * 部分 AGP 版本会跳过 preDebugAndroidTestBuild；把依赖同时挂到实际资产合并任务，
 * 确保直接执行 connectedDebugAndroidTest 时也必定使用离线端生成的 Fixture。
 */
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(prepareRagTestAssets)
}

tasks.matching { it.name == "preBuild" || it.name == "preDebugBuild" || it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyRagSharedSchema)
}

dependencies {
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
//    androidTestImplementation(libs.ext.junit)
//    androidTestImplementation(libs.espresso.core)
 //   androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // LangChain4j
    implementation(libs.langchain4j.openai)
    implementation(libs.langchain4j.core)

    implementation(files("libs/CameraSdk.jar"))
    implementation(files("libs/adapter_vr.jar"))
    implementation("com.google.code.gson:gson:2.8.9")
    implementation(libs.okhttp)
    implementation("dev.langchain4j:langchain4j-http-client:1.1.0")

    // OpenTelemetry
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
}
