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

        // 只打包 arm64-v8a（现代真机），移除 x86/x86_64（模拟器）和 armeabi-v7a（旧 32 位）
        // 此配置直接节省约 27MB（ML Kit native .so 占大头）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
