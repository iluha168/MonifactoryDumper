@file:Suppress("UnstableApiUsage")

rootProject.name = "MonifactoryDumper"

include("dumper")
include("dumper:deps:headlessglfw")
include("dumper:deps:faketime")
include("dumper:deps:downloader")
include("dumper:deps:imgencoder")

include("integrations:discord")

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // To install correct JDK version to launch Minecraft, as well as for Gradle itself (they are different, okay?).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_PROJECT
    repositories {
        mavenCentral()
    }
}