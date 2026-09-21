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
    // Never packed in: Forge already has ASM on the module path, and a second copy would be a duplicate module.
    compileOnly(libs.asm)
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "com.iluha168.monifactory.faketime.agent.ClockAgent",
            // The jar is also a module in the game's MC-BOOTSTRAP layer, which is where FakeTime's one live copy is
            // and what the renderer mod compiles against. A stable name, not one guessed from the file name.
            "Automatic-Module-Name" to "com.iluha168.monifactory.faketime",
        )
    }
}


// The test boots nothing. It rebuilds the part of the game's shape the agent cares about: a module layer holding the
// agent jar as a module, next to modules that stand in for Minecraft and for a named module that cannot read it.
val fixture = sourceSets.create("fixture")
val sealedFixture = sourceSets.create("sealedFixture")

val fixtureJar = tasks.register<Jar>("fixtureJar") {
    from(fixture.output)
    archiveFileName = "fixture.jar"
    destinationDirectory = layout.buildDirectory.dir("fixtures")
    // Like the Minecraft jar at boot: an automatic module, which reads every other module.
    manifest.attributes("Automatic-Module-Name" to "faketime.fixture")
}
val sealedFixtureJar = tasks.register<Jar>("sealedFixtureJar") {
    from(sealedFixture.output)
    archiveFileName = "sealed-fixture.jar"
    destinationDirectory = layout.buildDirectory.dir("fixtures")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.asm)
}

tasks.test {
    useJUnitPlatform()
    jvmArgumentProviders.add(AgentUnderTest(files(tasks.jar), files(fixtureJar), files(sealedFixtureJar)))
}

/** Kept out of the task body so the configuration cache has a class to serialize, not a script. */
class AgentUnderTest(
    @get:InputFiles @get:Classpath val agent: FileCollection,
    @get:InputFiles @get:Classpath val fixture: FileCollection,
    @get:InputFiles @get:Classpath val sealedFixture: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf(
        "-javaagent:" + agent.singleFile.absolutePath,
        "-Dfaketime.test.agent=" + agent.singleFile.absolutePath,
        "-Dfaketime.test.fixture=" + fixture.singleFile.absolutePath,
        "-Dfaketime.test.sealedFixture=" + sealedFixture.singleFile.absolutePath,
    )
}
