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
}
