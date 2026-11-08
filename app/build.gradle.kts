import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.motionguardian.kid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.motionguardian.kid"
        minSdk = 26            // MediaPipe 要求 >=24，CameraX >=21；取 26 以用上 Java 8 时间 API
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 调试包单独包名后缀，可以和正式包同时装在一台机器上
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
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

    buildFeatures {
        // XML 布局的类型安全绑定，全程用它，不用 findViewById
        viewBinding = true
    }

    androidResources {
        // 【关键】.task 模型不能压缩，否则 MediaPipe 读不到资源
        noCompress += "task"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- 基础 ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    // ---- Fragment / 导航 ----
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // ---- 列表 ----
    implementation(libs.androidx.recyclerview)

    // ---- 生命周期 / ViewModel ----
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // ---- 协程 ----
    implementation(libs.kotlinx.coroutines.android)

    // ---- 依赖注入 ----
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // ---- 相机 ----
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ---- 端侧姿态识别 ----
    implementation(libs.mediapipe.tasks.vision)

    // ---- 测试 ----
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}