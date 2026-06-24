import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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

        buildConfigField("String", "DASHSCOPE_API_KEY", "\"${localProps.getProperty("dashscope.api_key", "")}\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(projectDir.toString() + "/../platform.jks")
            storePassword = localProps.getProperty("signing.storePassword", "")
            keyAlias = localProps.getProperty("signing.keyAlias", "")
            keyPassword = localProps.getProperty("signing.keyPassword", "")
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
        buildConfig = true
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
