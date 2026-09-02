@file:Suppress("UnstableApiUsage")

plugins {
    base
}


val botInputs = fileTree(layout.projectDirectory) {
    include("src/**")
    include("deno.json")
}

val checkTypes = tasks.register<Exec>("checkTypes") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Type-checks the bot."

    inputs.files(botInputs)

    workingDir = layout.projectDirectory.asFile
    executable = "deno"
    args("check")
}

val checkFormat = tasks.register<Exec>("checkFormat") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the formatting."

    inputs.files(botInputs)

    workingDir = layout.projectDirectory.asFile
    executable = "deno"
    args("fmt", "--check", "--quiet", "--fail-fast")
}

tasks.check {
    dependsOn(checkTypes, checkFormat)
}


val depScope = configurations.dependencyScope("scope")

val dumpArtifact = configurations.resolvable("dumpArtifact") {
    extendsFrom(depScope.get())
}

dependencies {
    depScope.name(project(path = ":dumper", configuration = "dumpArtifact"))
}

tasks.register<Exec>("run") {
    dependsOn(tasks.check)

    group = "serve"
    description = "Runs the Discord bot."

    val artifact = dumpArtifact.get().incoming.files
    inputs.files(artifact)
    outputs.upToDateWhen { false }

    val envTemplate = layout.projectDirectory.file(".env.template").asFile
    inputs.file(envTemplate)

    workingDir = layout.projectDirectory.asFile
    executable = "deno"
    argumentProviders.add(CommandLineArgumentProvider {
        val envKeys = envTemplate.readLines()
            .filterNot { it.trimStart().startsWith("#") }
            .map { it.substringBefore('=').trim() }

        listOf(
            "run",
            "--env-file=.env",
            "--allow-env=${envKeys.joinToString(",")}",
            "--allow-read=${artifact.singleFile.absolutePath}",
            "--allow-net",
            "src/index.mts",
            artifact.singleFile.absolutePath,
        )
    })
}
