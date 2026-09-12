plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.slmchat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.slmchat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Required for MediaPipe LLM (arm64 only, large native lib)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Allow large model download on debug without minify noise
            isMinifyEnabled = false
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
        compose = true
    }
    packaging {
        resources {
            // MediaPipe AAR duplicates
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Keep only arm64 to cut APK size; LLM GPU delegate needs arm64
            keepDebugSymbols += setOf("arm64-v8a")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // On-device LLM — MediaPipe LLM Inference (offline, GPU/CPU)
    // In maintenance mode per Google; successor is LiteRT-LM.
    // Pinned to 0.10.27 (documented stable). 0.10.3x also works.
    implementation(libs.mediapipe.tasks.genai)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    ksp(libs.androidx.room.compiler)

    // Model downloader
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
}
