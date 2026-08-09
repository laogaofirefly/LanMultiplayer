plugins {
    id("com.android.application")
    // AGP 9 provides built-in Kotlin. Do not apply org.jetbrains.kotlin.android,
    // otherwise the Kotlin extension is registered twice.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.lanmultiplayer"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; prefab = true }
    externalNativeBuild { cmake { path = file("../native/CMakeLists.txt"); version = "3.22.1" } }

    defaultConfig {
        applicationId = "com.example.lanmultiplayer"
        minSdk = 23
        targetSdk = 36
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        versionCode = 1
        versionName = "0.1.0"
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
}