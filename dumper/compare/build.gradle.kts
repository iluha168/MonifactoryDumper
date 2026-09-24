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

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Two builds of one pack version never match byte for byte, so this is how they are compared: as sets. Any two
 * artifact directories: `./gradlew :dumper:compare:compareDumps -Pmonifactory.compare.a=<dir> -Pmonifactory.compare.b=<dir>`.
 * `./gradlew :dumper:rebuildCheck` is the usual way in: it builds the current pack twice and runs this on the pair.
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
    val report = layout.buildDirectory.file("compare.tsv").get().asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--a", a.orNull ?: throw GradleException("-Pmonifactory.compare.a=<artifact dir> is required"),
            "--b", b.orNull ?: throw GradleException("-Pmonifactory.compare.b=<artifact dir> is required"),
            "--out", report)
    })
    // Never up to date: its inputs are directories outside the build that it is told about at the command line.
    outputs.upToDateWhen { false }
}

/**
 * Re-checks the loop detection rule offline on identical pixels, from the hash sequences one boot of
 * `./gradlew :dumper:runGame -Pmonifactory.mode=seq` writes to dumper/build/render/seq.csv. Run it on every pack bump:
 * `./gradlew :dumper:compare:checkDetection [-Pmonifactory.seq=<seq.csv>[,<seq.csv>...]]`.
 */
tasks.register<JavaExec>("checkDetection") {
    group = "modpack"
    description = "Checks the frame policy against the full strict rule on recorded sequences."

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.iluha168.monifactory.compare.CheckDetection"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)

    val seq = providers.gradleProperty("monifactory.seq")
        .orElse(project(":dumper").layout.buildDirectory.file("render/seq.csv").map { it.asFile.absolutePath })
    argumentProviders.add(CommandLineArgumentProvider { seq.get().split(",") })
    outputs.upToDateWhen { false }
}

/**
 * Checks any artifact directory the way :dumper:verifyDump checks the one it just built:
 * `./gradlew :dumper:compare:verifyArtifact -Pmonifactory.artifact=<dir>`.
 */
tasks.register<JavaExec>("verifyArtifact") {
    group = "verification"
    description = "Checks that every recipes.json picture of a format 2 artifact can be drawn from its stills, or that it has none in a data-only artifact."

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.iluha168.monifactory.compare.VerifyArtifact"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    maxHeapSize = "2G"

    val artifact = providers.gradleProperty("monifactory.artifact")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--artifact", artifact.orNull ?: throw GradleException("-Pmonifactory.artifact=<artifact dir> is required"))
    })
    outputs.upToDateWhen { false }
}
