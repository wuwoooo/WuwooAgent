plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agentime.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.agentime.ime"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // 设备端 OCR（中文 + 拉丁），首次运行可能按需下载模型
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
}
