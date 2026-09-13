// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RootEncoder (RTMP / SRT) se publica en JitPack
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.pedroSG94.*") }
        }
    }
}

rootProject.name = "NexoLive"

include(":app")
include(":engine")
