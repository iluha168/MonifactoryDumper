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


/**
 * Runs the bot over whatever [configuration] of :dumper hands over. The configuration's task builds that dump first,
 * unless it is up to date.
 */
fun registerRun(taskName: String, configuration: String, what: String) {
    val scope = configurations.dependencyScope("${taskName}Scope")
    val dump = configurations.resolvable("${taskName}Dump") {
        extendsFrom(scope.get())
    }
    dependencies {
        scope.name(project(path = ":dumper", configuration = configuration))
    }

    tasks.register<Exec>(taskName) {
        dependsOn(tasks.check)

        group = "serve"
        description = "Runs the Discord bot over $what."

        val artifact = dump.get().incoming.files
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
}

registerRun("run", "dumpArtifact", "the full dump")
// recipes.json without images, minutes to build instead of hours: enough to work on everything that is not a picture.
registerRun("runData", "dumpDataArtifact", "the data-only dump")
