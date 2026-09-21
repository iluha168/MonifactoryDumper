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
    implementation(project(":dumper:deps:imgencoder"))
    // The decoder side of the fork, which ImageIO finds through its service file. imgencoder keeps it off its API.
    runtimeOnly(libs.webp.imageio)
}

/**
 * Two builds of one pack version never match byte for byte, so this is how they are compared (PLAN section 7). It
 * defaults to comparing the current artifact against a copy of an earlier one:
 * `./gradlew :dumper:compare:compareDumps -Pmonifactory.compare.a=<old artifact dir>`.
 */
val compareDumps = tasks.register<JavaExec>("compareDumps") {
    group = "modpack"
    description = "Compares two artifact directories as sets; fails on any difference that is not a documented one."

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.iluha168.monifactory.compare.CompareDumps"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // A 90 MB recipes.json twice over, as objects.
    maxHeapSize = "4G"

    val a = providers.gradleProperty("monifactory.compare.a")
    val b = providers.gradleProperty("monifactory.compare.b")
        .orElse(project(":dumper").layout.buildDirectory.dir("dumps/version-TODO").map { it.asFile.absolutePath })
    val report = layout.buildDirectory.file("compare.tsv").get().asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--a", a.orNull ?: throw GradleException("-Pmonifactory.compare.a=<artifact dir> is required"),
            "--b", b.get(), "--out", report)
    })
    // Never up to date: its inputs are directories outside the build that it is told about at the command line.
    outputs.upToDateWhen { false }
}
