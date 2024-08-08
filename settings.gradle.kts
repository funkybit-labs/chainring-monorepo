pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}

rootProject.name = "funkybit"
include("backend")
include("integrationtests")
include("sequencercommon")
include("sequencer")
include("mocker")
include("telegrambot")
