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

val mcVersion = libs.versions.minecraft.get()
val forgeVersion = libs.versions.forge.get()

/** How the Forge installer names what it installs, and what the game is launched as. */
val versionName = "$mcVersion-forge-$forgeVersion"

/** Monifactory does not finish loading in a default heap. Override with -Pmonifactory.heap=4G. */
val maxHeap = providers.gradleProperty("monifactory.heap").getOrElse("8G")

minecraft {
    mappings("official", mcVersion)
}
renamer.classes(tasks.named<Jar>("jar")) {
    map.from(minecraft.dependency.toSrgFile)
    archiveClassifier = "srg"
}

repositories {
    minecraft.mavenizer(this)
    // The Mavenizer puts itself first and publishes net.minecraftforge:forge under the same
    // coordinates the installer lives at, so a lookup for the installer classifier stops there and
    // fails. Forge's own maven has to be asked first for the artifact to be found at all.
    val forgeMaven = maven(fg.forgeMaven)
    remove(forgeMaven)
    addFirst(forgeMaven)
    maven(fg.minecraftLibsMaven)
    mavenCentral()
}

dependencies {
    implementation(
        minecraft.dependency("net.minecraftforge:forge:$mcVersion-$forgeVersion")
    )
}

// Forge's own tools, each driven through its published command line.
val mavenizerScope = configurations.dependencyScope("mavenizer")
val forgeInstallerScope = configurations.dependencyScope("forgeInstaller")
val slimeScope = configurations.dependencyScope("slimeLauncher")
val packScope = configurations.dependencyScope("packScope")
val headlessGlfwScope = configurations.dependencyScope("headlessGlfw")

val mavenizerPath = configurations.resolvable("mavenizerPath") { extendsFrom(mavenizerScope.get()) }
val forgeInstallerPath = configurations.resolvable("forgeInstallerPath") { extendsFrom(forgeInstallerScope.get()) }
val slimePath = configurations.resolvable("slimePath") { extendsFrom(slimeScope.get()) }
val pack = configurations.resolvable("pack") { extendsFrom(packScope.get()) }
val headlessGlfwPath = configurations.resolvable("headlessGlfwPath") {
    extendsFrom(headlessGlfwScope.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    mavenizerScope.name(libs.mavenizer)
    forgeInstallerScope.name("net.minecraftforge:forge:$mcVersion-$forgeVersion:installer@jar")
    slimeScope.name(libs.slime.launcher)
    packScope.name(project(path = ":dumper:deps:downloader", configuration = "pack"))
    headlessGlfwScope.name(project(":dumper:deps:headlessglfw"))
}


// Vanilla: the client library set, the launcher's version.json and the unmodified client jar.
val vanillaDir = layout.buildDirectory.dir("minecraft")

val vanillaFiles = tasks.register<JavaExec>("vanillaFiles") {
    group = "modpack"
    description = "Gathers the vanilla client files with Forge's Minecraft Mavenizer."

    classpath = mavenizerPath.get()
    mainClass = "net.minecraftforge.mcmaven.cli.Main"
    // The Mavenizer is built for a much newer JVM than the game runs on, so it gets its own.
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(libs.versions.mavenizer.java.get().toInt())
    }

    outputs.dir(vanillaDir)
    args(
        "--minecraft-files",
        "--version", mcVersion,
        "--output-dir", vanillaDir.get().asFile.absolutePath,
        "--output", vanillaDir.get().file("files.json").asFile.absolutePath,
        "--cache", vanillaDir.get().dir("cache").asFile.absolutePath,
    )
}

// Forge: the official installer, not the Mavenizer's artifact. The Mavenizer recompiles Forge from
// patched sources for the dev road; only the installer's binpatched client is what players run.
val forgeDir = layout.buildDirectory.dir("forge")

val installForge = tasks.register<JavaExec>("installForge") {
    group = "modpack"
    description = "Runs forge-$mcVersion-$forgeVersion-installer.jar --installClient."

    classpath = forgeInstallerPath.get()
    mainClass = "net.minecraftforge.installer.SimpleInstaller"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)

    val target = forgeDir.get().asFile
    outputs.dir(target)
    args("--installClient", target.absolutePath)

    // It writes a log beside wherever it was started from, which is the project directory by default.
    workingDir = target

    val profiles = forgeDir.get().file("launcher_profiles.json").asFile
    doFirst {
        // The installer refuses to run without one, and writes its profile into it.
        target.mkdirs()
        profiles.writeText("""{"profiles":{},"version":3}""")
    }
}

// The instance: the pack, and whatever the game writes next to it. Forge and vanilla stay in their
// own directories so no two tasks ever write to the same tree.
val instanceDir = layout.buildDirectory.dir("instance")

val installPack = tasks.register<Sync>("installPack") {
    group = "modpack"
    description = "Lays the downloaded pack into the instance directory."

    from(pack.get().incoming.files)
    into(instanceDir)

    // Sync so a mod dropped from the pack does not linger and break the next boot. Everything else
    // in there belongs to the game - logs, saves, screenshots - and stays put.
    preserve {
        exclude("mods/**")
        exclude("resourcepacks/**")
        exclude("shaderpacks/**")
    }
}


val launchArgs = layout.buildDirectory.file("launch.args")

/**
 * Where LWJGL, JNA and netty unpack their natives. Vanilla's own JVM arguments point all three at
 * the launcher's natives directory and Forge's do not, so without this they land in java.io.tmpdir,
 * which is mounted noexec on some machines.
 */
val nativesDir = layout.buildDirectory.dir("natives")

val writeLaunchArgs = tasks.register("writeLaunchArgs") {
    group = "modpack"
    description = "Turns the vanilla and Forge version manifests into a java @argfile."

    // files.json is the Mavenizer's index of what it wrote, so no vanilla path here is guessed.
    val filesJson = vanillaFiles.map { vanillaDir.get().file("files.json").asFile }
    fun vanillaFile(key: String) = filesJson.map { index ->
        vanillaDir.get().asFile.resolve((groovy.json.JsonSlurper().parse(index) as Map<*, *>)[key] as String)
    }
    val vanillaJson = vanillaFile("version")
    val vanillaLibList = vanillaFile("client.libraries")
    val forgeJson = installForge.map {
        forgeDir.get().file("versions/$versionName/$versionName.json").asFile
    }

    val argFile = launchArgs.get().asFile
    val metadataDir = layout.buildDirectory.dir("slime-metadata").get().asFile
    outputs.file(argFile)
    outputs.dir(metadataDir)

    val vanillaRoot = vanillaDir.get().asFile
    val forgeLibraries = forgeDir.get().dir("libraries").asFile
    val instance = instanceDir.get().asFile
    val assetsDir = layout.buildDirectory.dir("assets").get().asFile
    val slimeCache = layout.buildDirectory.dir("slime-cache").get().asFile
    val natives = nativesDir.get().asFile
    val slimeJars = slimePath.get().incoming.files
    // The fake GLFW goes last on -cp, since the jar a merged module meets last wins each class
    // they both carry and names the module. Its companions - the EGL binding - go right before it.
    val fakeGlfw = headlessGlfwPath.get().incoming.artifactView {
        componentFilter { it is ProjectComponentIdentifier }
    }.files
    val fakeGlfwCompanions = headlessGlfwPath.get().incoming.artifactView {
        componentFilter { it !is ProjectComponentIdentifier }
    }.files
    val version = versionName
    val heap = maxHeap

    // The argfile is these files, these strings and these directories and nothing else. All of them
    // are declared, so a newer Slime Launcher or a different heap rewrites it instead of leaving
    // yesterday's command behind an up-to-date check.
    inputs.files(filesJson, vanillaJson, vanillaLibList, forgeJson, slimeJars, fakeGlfw, fakeGlfwCompanions)
    inputs.property("versionName", version)
    inputs.property("heap", heap)
    inputs.property(
        "directories",
        listOf(vanillaRoot, forgeLibraries, instance, assetsDir, slimeCache, natives).map { it.absolutePath },
    )

    doLast {
        val slurper = groovy.json.JsonSlurper()
        val vanilla = slurper.parse(vanillaJson.get()) as Map<*, *>
        val forge = slurper.parse(forgeJson.get()) as Map<*, *>

        // Slime Launcher only ever reads minecraft/version.json out of its metadata directory.
        metadataDir.resolve("minecraft").mkdirs()
        vanillaJson.get().copyTo(metadataDir.resolve("minecraft/version.json"), overwrite = true)

        val osName = System.getProperty("os.name").lowercase().let {
            when {
                it.startsWith("windows") -> "windows"
                it.startsWith("mac") || it.startsWith("darwin") -> "osx"
                else -> "linux"
            }
        }
        val osArch = when (val arch = System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "arm64"
            "i386", "i486", "i586", "i686", "x86" -> "x86"
            else -> arch
        }
        // The launcher's own rule evaluation: no rules means always, otherwise the last rule that
        // matches this machine decides. Feature rules gate demo mode and a custom resolution, and
        // this build enables neither, so a rule carrying one never matches.
        val allows = { rules: Any? ->
            (rules as? List<*>)?.filterIsInstance<Map<*, *>>()?.fold(false) { allowed, rule ->
                val os = rule["os"] as? Map<*, *>
                val matches = rule["features"] == null
                    && (os?.get("name") as String? ?: osName) == osName
                    && (os?.get("arch") as String? ?: osArch) == osArch
                if (matches) rule["action"] == "allow" else allowed
            } ?: true
        }

        // The Mavenizer lists every client library for every platform. The launcher passes only the
        // ones its rules allow, and so does this: on Linux that drops 36 of the 88, all Windows and
        // macOS natives plus the macOS objc bridge, none of which a player's classpath carries.
        val foreign = (vanilla["libraries"] as List<*>).filterIsInstance<Map<*, *>>()
            .filterNot { allows(it["rules"]) }
            .map { ((it["downloads"] as Map<*, *>)["artifact"] as Map<*, *>)["path"] as String }

        val classpath = buildList {
            vanillaLibList.get().readLines()
                .filter { line -> line.isNotBlank() && foreign.none(line::endsWith) }
                .forEach { add(vanillaRoot.resolve(it)) }
            @Suppress("UNCHECKED_CAST")
            (forge["libraries"] as List<Map<String, Any>>).forEach {
                val artifact = (it["downloads"] as Map<*, *>)["artifact"] as Map<*, *>
                add(forgeLibraries.resolve(artifact["path"] as String))
            }
            addAll(slimeJars)
            addAll(fakeGlfwCompanions)
            addAll(fakeGlfw)
        }
        val missing = classpath.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("the version manifests name ${missing.size} libraries that were not installed: $missing")
        }

        val placeholders = mapOf(
            "library_directory" to forgeLibraries.absolutePath,
            "classpath_separator" to File.pathSeparator,
            "version_name" to version,
            "game_directory" to instance.absolutePath,
            "auth_player_name" to "Dumper",
            "auth_uuid" to "00000000-0000-0000-0000-000000000000",
            "auth_access_token" to "0",
            "auth_xuid" to "0",
            "clientid" to "0",
            "user_type" to "legacy",
            "version_type" to "release",
            // Slime Launcher fills these in once it knows where the assets landed.
            "assets_root" to "{assets_root}",
            "assets_index_name" to "{asset_index}",
        )
        val expand = { value: String ->
            placeholders.entries.fold(value) { acc, (key, replacement) -> acc.replace("\${$key}", replacement) }
        }
        // Entries that are objects rather than strings are all rule-gated features - demo mode, a
        // custom resolution - and this build enables none of them.
        val strings = { arguments: Any? ->
            (((arguments as? Map<*, *>)?.get("game")) as? List<*>).orEmpty().filterIsInstance<String>().map(expand)
        }

        // BootstrapLauncher keeps ignoreList jars on the app classloader and out of the module
        // layer. Slime Launcher hands off in-process, so its jars are already loaded off -cp by the
        // time Forge starts and must not be unioned into a module as well. Appended, so Forge's own
        // entries keep their order.
        val ignoreList = "-DignoreList="
        var hidden = false
        // A module cannot split a package, so a second jar carrying org.lwjgl.glfw could never sit
        // beside the real one in the module layer. BootstrapLauncher's -DmergeModules makes one
        // module of several jars instead, matched by exact file name. The client's own GLFW jar
        // stays in the group for the classes the fake does not replace (GLFWVidMode, the callback
        // types), which mods' mixins read as metadata.
        val mergeModules = "-DmergeModules="
        val glfwJar = classpath.map { it.name }.singleOrNull { Regex("""lwjgl-glfw-[0-9.]+\.jar""").matches(it) }
            ?: throw GradleException("the client classpath has no lwjgl-glfw jar for the fake GLFW to merge into")
        val lwjglVersion = glfwJar.removePrefix("lwjgl-glfw-").removeSuffix(".jar")
        if (fakeGlfwCompanions.none { it.name == "lwjgl-egl-$lwjglVersion.jar" }) {
            throw GradleException("the client ships LWJGL $lwjglVersion but headlessglfw brings ${fakeGlfwCompanions.map { it.name }}; bump lwjgl in libs.versions.toml")
        }
        val mergeGroup = (listOf(glfwJar) + fakeGlfwCompanions.map { it.name } + fakeGlfw.map { it.name }).joinToString(",")
        var merged = false
        @Suppress("UNCHECKED_CAST")
        val jvm = ((forge["arguments"] as Map<*, *>)["jvm"] as List<String>).map(expand).map { argument ->
            when {
                argument.startsWith(ignoreList) -> {
                    hidden = true
                    argument + slimeJars.joinToString("") { "," + it.name }
                }
                argument.startsWith(mergeModules) -> {
                    merged = true
                    "$argument;$mergeGroup"
                }
                else -> argument
            }
        }.let { if (merged) it else it + (mergeModules + mergeGroup) }
        if (!hidden) {
            throw GradleException("$version.json passes no $ignoreList, so the launcher jars have nowhere to hide")
        }

        val command = buildList {
            addAll(jvm)
            // Monifactory does not finish loading in a default heap.
            add("-Xmx$heap")
            // java.library.path stays unset on purpose - it is what wakes Slime Launcher's own
            // native extraction, and LWJGL 3 unpacks itself.
            add("-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives.absolutePath}")
            add("-Djna.tmpdir=${natives.absolutePath}")
            add("-Dio.netty.native.workdir=${natives.absolutePath}")
            add("-cp")
            add(classpath.joinToString(File.pathSeparator) { it.absolutePath })

            add("net.minecraftforge.launcher.Main")
            add("--cache"); add(slimeCache.absolutePath)
            add("--metadata"); add(metadataDir.absolutePath)
            add("--main"); add(forge["mainClass"] as String)
            add("--assets"); add(assetsDir.absolutePath)
            add("--launcher-side"); add("client")
            add("--")
            addAll(strings(vanilla["arguments"]))
            addAll(strings(forge["arguments"]))
        }

        argFile.parentFile.mkdirs()
        argFile.writeText(command.joinToString("\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
    }
}

val runGame = tasks.register<Exec>("runGame") {
    group = "modpack"
    description = "Boots the real Monifactory install. Assets download on the first run."

    dependsOn(writeLaunchArgs, installPack)

    val instance = instanceDir.get().asFile
    val natives = nativesDir.get().asFile
    doFirst {
        instance.mkdirs()
        natives.mkdirs()
    }
    doFirst(HeadlessInstance(instance))

    workingDir = instance
    executable = javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.absolutePath
    argumentProviders.add(ArgFile(launchArgs))

    // The fake GLFW never talks to a display server, so the game gets none. Anything in the pack
    // that still reaches for one - AWT, tinyfd - fails where it can be seen instead of opening a
    // window on whoever's desktop the build happens to run.
    environment.remove("DISPLAY")
    environment.remove("WAYLAND_DISPLAY")
}

/**
 * The two settings a headless client needs that are not launch arguments. Neither file comes with
 * the pack - the game writes both on its first start - so setting one key in each edits no pack
 * config, and every other line a player or the game put there stays.
 *
 * Without earlyWindowControl=false, FML opens its loading window itself, through a GLFW the fake
 * does not implement. Without onboardAccessibility:false a fresh instance parks on the
 * accessibility onboarding screen and never shows the title screen.
 */
class HeadlessInstance(private val instance: File) : Action<Task> {
    override fun execute(task: Task) {
        set(instance.resolve("config/fml.toml"), "earlyWindowControl", " = ", "false")
        set(instance.resolve("options.txt"), "onboardAccessibility", ":", "false")
    }

    private fun set(file: File, key: String, separator: String, value: String) {
        val line = Regex("""^\s*""" + Regex.escape(key) + """\s*""" + Regex.escape(separator.trim()) + ".*$")
        val lines = if (file.isFile) file.readLines() else emptyList()
        val wanted = key + separator + value
        val updated = if (lines.any(line::matches)) lines.map { if (line.matches(it)) wanted else it } else lines + wanted
        if (updated != lines) {
            file.parentFile.mkdirs()
            file.writeText(updated.joinToString("\n", postfix = "\n"))
        }
    }
}

/** Kept out of the task body so the configuration cache has a class to serialize, not a script. */
class ArgFile(private val file: Provider<RegularFile>) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("@" + file.get().asFile.absolutePath)
}


// TODO Version the artifact by the pack version the dump came from.
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
