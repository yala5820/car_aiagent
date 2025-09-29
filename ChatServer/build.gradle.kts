plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.chatserver"
    compileSdk = 35

    defaultConfig {
        minSdk = 31

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

//    implementation("dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q:1.1.0-beta7") {
//        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime")
//    }
//    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
//    implementation("ai.djl.android:tokenizer-native:0.33.0")
//    implementation("dev.langchain4j:langchain4j-chroma:1.1.0-beta7")
//    implementation("dev.langchain4j:langchain4j-mcp:1.1.0-beta7")
//    implementation(project(":android_document_loader"))

    implementation("dev.langchain4j:langchain4j-open-ai:1.1.0")
    implementation("dev.langchain4j:langchain4j:1.1.0")
    implementation(project(":http-client-ok"))
    implementation(project(":chat_memory_sqlite"))
    implementation(project(":weatherutils"))
    implementation(project(":VehicleDoorManager"))
    implementation(project(":VehicleWindowManager"))
    implementation(project(":VehicleSeatManager"))
    implementation(project(":VehicleAcManager"))
    implementation(project(":VehicleFragManager"))
    implementation(project(":VlManager"))
}