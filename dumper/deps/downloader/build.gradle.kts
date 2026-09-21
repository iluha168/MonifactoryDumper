@file:Suppress("UnstableApiUsage")

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

plugins {
    `java-library`
}

group = "com.iluha168.monifactory"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    implementation(libs.gson)
}


val latestJson = layout.buildDirectory.file("latest.json")

val fetchLatest = tasks.register<JavaExec>("fetchLatest") {
    group = "modpack"
    description = "Asks CurseForge which Monifactory file is current."

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.iluha168.monifactory.downloader.FetchLatest"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)

    val answer = latestJson.get().asFile
    outputs.file(answer)
    outputs.upToDateWhen {
        // If you have come here after a new release to find out why it is not re-fetching, --rerun this task
        answer.isFile && (System.currentTimeMillis() - answer.lastModified()).milliseconds < 1.days
    }

    args(
        "--project", libs.versions.modpack.curseforge.get(),
        "--out", answer.absolutePath,
    )
}

val packDir = layout.buildDirectory.dir("pack")
val packZip = layout.buildDirectory.file("monifactory-client.zip")

val downloadPack = tasks.register<JavaExec>("downloadPack") {
    group = "modpack"
    description = "Assembles a Monifactory instance directory from the current pack file."

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.iluha168.monifactory.downloader.Downloader"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)

    val latest = fetchLatest.map { it.outputs.files.singleFile }
    inputs.file(latest)
    outputs.file(packZip)
    outputs.dir(packDir)

    args(
        "--latest", latestJson.get().asFile.absolutePath,
        "--zip", packZip.get().asFile.absolutePath,
        "--pack-dir", packDir.get().asFile.absolutePath,
        "--minecraft", libs.versions.minecraft.get(),
        "--forge", libs.versions.forge.get(),
    )
}

val pack = configurations.consumable("pack")

artifacts {
    add(pack.name, packDir) {
        builtBy(downloadPack)
    }
}
