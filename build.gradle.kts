// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

// AGP 9 trae Kotlin integrado: no se aplica org.jetbrains.kotlin.android.
// Declarar el plugin de Compose aquí fija la versión de Kotlin (2.4.x) para todo el proyecto.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
