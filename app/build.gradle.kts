plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersion = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.sudosilo.cliprig"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sudosilo.cliprig"
        minSdk = 29
        targetSdk = 36
        versionCode = ciVersion
        versionName = "v$ciVersion"
    }

    signingConfigs {
        create("cliprig") {
            storeFile = file("2026-11-25-blue-cliprig-v1.keystore")
            storePassword = "cliprigpass"
            keyAlias = "cliprig"
            keyPassword = "cliprigpass"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("cliprig")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("cliprig")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val media3 = "1.8.0"
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
}
