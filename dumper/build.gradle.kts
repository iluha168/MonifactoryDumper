@file:Suppress("UnstableApiUsage")

plugins {
    `java-library`
    alias(libs.plugins.forgegradle)
    alias(libs.plugins.renamer)
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

minecraft {
    mappings("official", libs.versions.minecraft.get())

    // Yes, unnecessary run configurations, it is simply for testing.
    runs {
        register("client") {
            workingDir.convention(layout.buildDirectory.dir("run"))
        }
    }
}
renamer.classes(tasks.named<Jar>("jar")) {
    map.from(minecraft.dependency.toSrgFile)
    archiveClassifier = "srg"
}

repositories {
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
    mavenCentral()
}

dependencies {
    implementation(
        minecraft.dependency("net.minecraftforge:forge:${libs.versions.minecraft.get()}-${libs.versions.forge.get()}")
    )
}

// TODO CurseForge integration.
val artifactDir = layout.buildDirectory.dir("dumps/version-TODO")

val dump = tasks.register("dump") {
    group = "modpack"
    description = "Renders the recipe corpus into a versioned artifact directory. Stub."

    val outputDir = artifactDir // Weird thing crashes build if inlined
    outputs.dir(outputDir)

    doLast {
        // TODO File will be produced by the real dumper.
        outputDir.get().file("recipes.json").asFile.writeText("""["T","O","D","O"]""")
    }
}

val dumpArtifact = configurations.consumable("dumpArtifact")

artifacts {
    add(dumpArtifact.name, artifactDir) {
        builtBy(dump)
    }
}
