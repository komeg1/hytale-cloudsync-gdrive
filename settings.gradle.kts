pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the JDK 25 toolchain automatically if it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "CloudSaveSync"

// Provider-agnostic sync logic (no Hytale or Google dependencies).
include(":sync-core")
// Google Drive storage provider and Google sign-in.
include(":gdrive")
// Thin Hytale plugin layer plus the detached upload helper.
include(":plugin")
