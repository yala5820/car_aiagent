plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hirain.aiagent.sceneserver"
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

    implementation("dev.langchain4j:langchain4j-open-ai:1.1.0")
    implementation("dev.langchain4j:langchain4j:1.1.0")
    implementation(project(":http-client-ok"))
    implementation(project(":chat_memory_sqlite"))
    implementation(project(":VehicleDoorManager"))
    implementation(project(":VehicleWindowManager"))
    implementation(project(":VehicleSeatManager"))
    implementation(project(":VehicleAcManager"))
    implementation(project(":VehicleFragManager"))
    implementation(project(":SceneMatch"))
    // ...其他依赖
}