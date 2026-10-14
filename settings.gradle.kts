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

include(":plugin")
// Phase 1: include(":sync-core")
