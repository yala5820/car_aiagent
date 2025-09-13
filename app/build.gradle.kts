plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.example.desugar-transform")
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
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
//    androidTestImplementation(libs.ext.junit)
//    androidTestImplementation(libs.espresso.core)
 //   androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("org.ow2.asm:asm:9.5")
    implementation("org.ow2.asm:asm-commons:9.5")

    // ...其他依赖
    implementation("dev.langchain4j:langchain4j-open-ai:1.1.0")
    implementation("dev.langchain4j:langchain4j:1.1.0")

//    implementation("dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q:1.1.0-beta7") {
//        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime")
//    }
//    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
//    implementation("ai.djl.android:tokenizer-native:0.33.0")
//    implementation("dev.langchain4j:langchain4j-chroma:1.1.0-beta7")
//    implementation("dev.langchain4j:langchain4j-mcp:1.1.0-beta7")
    implementation(files("libs/CameraSdk.jar"))
    implementation(files("libs/AIAgentSdk.jar"))

    implementation(project(":http-client-ok"))
    implementation(project(":chat_memory_sqlite"))
//    implementation(project(":android_document_loader"))
    implementation(project(":weatherutils"))
    implementation(project(":VehicleDoorManager"))
    implementation(project(":VehicleWindowManager"))
    implementation(project(":VehicleSeatManager"))
    implementation(project(":VehicleAcManager"))
    implementation(project(":VehicleFragManager"))
    implementation(project(":VlManager"))
}
