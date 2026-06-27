plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Compose, Hilt, Room, KSP — disabled for sensor probe build
    // alias(libs.plugins.kotlin.compose)
    // alias(libs.plugins.kotlin.serialization)
    // alias(libs.plugins.hilt)
    // alias(libs.plugins.ksp)
}

android {
    namespace = "com.av"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.av"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-probe"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
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

    // Compose disabled for probe
    // buildFeatures {
    //     compose = true
    // }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)

    // Coroutines (used by SensorCollector)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Everything below is DISABLED for probe build
    // implementation(libs.androidx.lifecycle.runtime.ktx)
    // implementation(libs.androidx.lifecycle.runtime.compose)
    // implementation(libs.androidx.lifecycle.service)
    // implementation(libs.androidx.activity.compose)
    // implementation(platform(libs.androidx.compose.bom))
    // implementation(libs.androidx.ui)
    // implementation(libs.androidx.ui.graphics)
    // implementation(libs.androidx.ui.tooling.preview)
    // implementation(libs.androidx.material3)
    // implementation(libs.androidx.material.icons.extended)
    // implementation(libs.hilt.android)
    // ksp(libs.hilt.android.compiler)
    // implementation(libs.hilt.navigation.compose)
    // implementation(libs.room.runtime)
    // implementation(libs.room.ktx)
    // ksp(libs.room.compiler)
    // implementation(libs.kotlinx.serialization.json)
}
