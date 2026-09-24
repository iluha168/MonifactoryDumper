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
    // Not api: no webp-imageio type appears in this project's public signatures.
    // It pulls kotlin-stdlib transitively. That is fine here and in tests, but inside the game Kotlin for Forge
    // already exports the kotlin.* packages, so whoever ships this jar into the game must leave kotlin-stdlib out.
    implementation(libs.webp.imageio)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "com.iluha168.monifactory.imgencoder")
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Opt-in check of the muxer against recorded frame sets and the bytes it must write for them, which live outside
    // the repository: -Pmonifactory.enc.corpus=<dir holding work/ and work2/>, laid out as README.md says. What the
    // test writes goes to build/enc-corpus, where another tool can decode it independently.
    val corpus = providers.gradleProperty("monifactory.enc.corpus")
    if (corpus.isPresent) {
        inputs.dir(corpus)
        systemProperty("monifactory.enc.corpus", corpus.get())
        systemProperty("monifactory.enc.out", layout.buildDirectory.dir("enc-corpus").get().asFile.path)
    }
}
