// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.senda.studio.engine"
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
    implementation(libs.rootencoder.rtmp)
    implementation(libs.rootencoder.srt)
    implementation(libs.uvc.android)

    testImplementation(libs.junit)
}
