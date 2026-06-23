plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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

    signingConfigs {

        getByName("debug") {
            storeFile = file(projectDir.toString() + "/../platform.jks")
            storePassword = "123456789"
            keyAlias = "123456789"
            keyPassword = "123456789"
        }

    }
    buildTypes {
        getByName("release") {
            // 使用debug签名配置，默认不需要设置，但如果你想用自定义的，可以这样：
        //    signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            // 使用debug签名配置，默认不需要设置，但如果你想用自定义的，可以这样：
             signingConfig = signingConfigs.getByName("debug")
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
    }
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

}
