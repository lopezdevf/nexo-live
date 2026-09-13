// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nexo.live.engine"
    compileSdk = 37

    defaultConfig {
        // API 29: captura de audio interno (AudioPlaybackCapture) disponible siempre
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.rootencoder.rtmp)
    implementation(libs.rootencoder.srt)

    testImplementation(libs.junit)
}
