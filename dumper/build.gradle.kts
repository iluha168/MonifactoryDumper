@file:Suppress("UnstableApiUsage")

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

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

/**
 * Each game's -Xmx, -Pmonifactory.heap. Monifactory does not finish loading in a default heap, and 5G is what the
 * measurements behind [GameMemory] were taken at. Less works down to 4G, see [GameMemory.heapWarning].
 */
val maxHeap = providers.gradleProperty("monifactory.heap").getOrElse("5G")

/**
 * JVM flags every game gets, one game or several. They change nothing a dump contains, so like the heap they are kept
 * out of the argfile, which is an input of every dump. They only do their part together with the renderer's
 * ServerLeftovers, whose one full collection after EMI's reload is where G1 can give memory back: 7.9 GB resident
 * while rendering drops to 7.0 GB at -Xmx5G.
 * - The free ratios let G1 return the heap the boot needed and the render does not: committed 5 GB -> about 4.7 GB.
 * - The trim hands what glibc's arenas have freed back to the system every 10 s, 256 to 342 MiB a time. It is a
 *   product flag from JDK 17.0.9 on.
 * Not MALLOC_ARENA_MAX=2: 140 MiB less resident, but the encoders then wait on malloc and the batch took 16% longer.
 */
val gameJvmFlags = listOf(
    "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
    "-XX:TrimNativeHeapInterval=10000",
)

/**
 * The environment every game gets on top of the build's. ALSOFT_DRIVERS=null puts OpenAL on its "No Output" device: the
 * same sound engine code runs, with no audio server to connect to and nothing playing on this machine's speakers.
 */
val gameEnvironment = mapOf("ALSOFT_DRIVERS" to "null")

/**
 * What one game needs, as measured on Monifactory 0.13.8 at -Xmx5G with [gameJvmFlags] and the renderer's server
 * release: about 8 GB resident at the boot's peak, which is over once the renderer logs that it released the server
 * side, and about 7.5 GB while it renders. Beyond the heap that is metaspace, the code cache and GC structures (about
 * 0.9 GB), and what the JVM does not see: LWJGL, the NVIDIA driver, the WebP encoders' malloc arenas and thread stacks
 * (about 1.5 GB). GPU memory is about 325 MiB a game and never the limit.
 */
object GameMemory {
    /** The heap the numbers here were measured at. */
    const val MEASURED_HEAP_MIB = 5L * 1024
    /** Resident beyond the heap while a game renders, and at its boot's peak. */
    const val RENDER_OVERHEAD_MIB = 2560L
    const val BOOT_OVERHEAD_MIB = 3072L
    /** Room left for everything else the machine runs while the games do. */
    const val HEADROOM_MIB = 2048L

    /** An -Xmx size in MiB, or null if it is not one this understands. */
    fun mib(size: String): Long? {
        val match = Regex("""(\d+)([kKmMgGtT]?)""").matchEntire(size.trim()) ?: return null
        val value = match.groupValues[1].toLong()
        return when (match.groupValues[2].lowercase()) {
            "k" -> value / 1024
            "m" -> value
            "g" -> value * 1024
            "t" -> value * 1024 * 1024
            else -> value / (1024 * 1024)
        }
    }

    /**
     * Why a heap below 5G is a bad idea, or null. At -Xmx4G the boot fills the heap: three full collections of 0.6 to
     * 0.8 s during EMI's reload, and a batch 9% slower than at 5G. 3G runs out during EMI's reload, and the game then
     * exits 0 without an artifact. 4G did not change the pictures: the 25 Thermal fuel pictures that differed in that
     * run show a 20-second JEI timer that starts at a different phase every boot (see the README).
     */
    fun heapWarning(size: String): String? {
        val heap = mib(size) ?: return null
        if (heap >= MEASURED_HEAP_MIB) return null
        return ("-Xmx$size is below the 5G a game should have. At 4G the boot fills the heap and needs full "
            + "collections, and the batch runs about 9% slower; at 3G it runs out during EMI's reload and the game "
            + "exits without an artifact. Use -Pmonifactory.heap=5G.")
    }
}

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
    // fails. Forge's own maven has to be asked first for that one artifact to be found at all - and
    // for nothing else, since Forge's maven has a forge pom without the classes, and the compile
    // classpath would end up with no Minecraft on it.
    val forgeInstallerMaven = maven {
        fg.forgeMaven.execute(this)
        name = "ForgeInstaller"
        content { onlyForConfigurations("forgeInstallerPath") }
    }
    remove(forgeInstallerMaven)
    addFirst(forgeInstallerMaven)
    maven(fg.forgeMaven)
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
val fakeTimeScope = configurations.dependencyScope("fakeTime")

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
val fakeTimePath = configurations.resolvable("fakeTimePath") {
    extendsFrom(fakeTimeScope.get())
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
    fakeTimeScope.name(project(":dumper:deps:faketime"))
}

dependencies {
    // Not packed in: the clock has to be the one copy the agent links every call site to, and at runtime that copy is
    // a module of its own in MC-BOOTSTRAP. See writeLaunchArgs.
    compileOnly(project(":dumper:deps:faketime"))
    // The renderer draws through EMI, so it compiles against the jar the pack ships, as it ships. Nothing deobfuscates
    // it: EMI's own member names are not Minecraft's, and the rename to SRG never touches them. It is taken from the
    // downloaded pack rather than the instance, because the instance is where this mod gets installed.
    compileOnly(pack.get().incoming.files.asFileTree.matching { include("mods/emi-*.jar") })
    // The encoder is packed in, see jarJar below.
    compileOnly(project(":dumper:deps:imgencoder"))
}

// Tests cover the pure classes only: the matte, the reconstruction check, the tile packing. Minecraft is on their
// classpath through implementation, but nothing a test loads touches it, and there is no game to boot.
dependencies {
    testImplementation(project(":dumper:deps:imgencoder"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Libraries the renderer carries inside its own jar, in Forge's jar-in-jar layout. Forge loads each nested jar that has
// no mods.toml as a GAMELIBRARY, a module in the GAME layer next to the mods. Neither -cp nor a jar dropped loose in
// mods/ would do: webp-imageio is written in Kotlin, and the pack's Kotlin for Forge is a LIBRARY in the PLUGIN layer
// that already carries every kotlin.* package. A GAME-layer module reads that one; a second kotlin-stdlib anywhere in
// the layer stack would be a split package. So kotlin-stdlib stays out, and the fork runs on KFF's Kotlin, which is
// newer than any stdlib call it makes.
val jarJarScope = configurations.dependencyScope("jarJar")
val jarJarPath = configurations.resolvable("jarJarPath") {
    extendsFrom(jarJarScope.get())
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains", module = "annotations")
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    jarJarScope.name(project(":dumper:deps:imgencoder"))
}

val jarJarMetadata = tasks.register<JarJarMetadata>("jarJarMetadata") {
    description = "Lists the nested jars for Forge's jar-in-jar locator."
    val ownGroup = project.group.toString()
    jars = jarJarPath.flatMap { path ->
        path.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.map { artifact ->
                when (val id = artifact.id.componentIdentifier) {
                    is ModuleComponentIdentifier -> listOf(id.group, id.module, id.version, artifact.file.name)
                    // This build's own jars carry no version. Nothing else ships them, so any version selects them.
                    else -> listOf(ownGroup, artifact.file.name.removeSuffix(".jar"), "0", artifact.file.name)
                }.joinToString(":")
            }
        }
    }
    output = layout.buildDirectory.file("jarjar/metadata.json")
}

tasks.jar {
    from(jarJarPath) { into("META-INF/jarjar") }
    from(jarJarMetadata) { into("META-INF/jarjar") }
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
    description = "Lays the downloaded pack and the renderer mod into the instance directory."

    from(pack.get().incoming.files)
    // The renderer is a mod like any other in there, in the names the production game runs with.
    from(tasks.named("renameJar")) {
        include("*.jar")
        into("mods")
    }
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
    // Through the task's output files, not the task: a provider of those knows vanillaFiles makes it, so the
    // configuration cache stores it unread and it is parsed when this task runs. Mapped off the task itself, it would
    // be read while the cache entry is written, and on a clean build files.json does not exist yet.
    // The lambdas capture plain Files, never the script's own values, which the cache cannot store.
    val vanillaRootDir = vanillaDir.get().asFile
    val vanillaIndex = vanillaRootDir.resolve("files.json")
    val filesJson = objects.fileCollection().from(vanillaFiles).elements.map { vanillaIndex }
    fun vanillaFile(key: String) = filesJson.map { index ->
        vanillaRootDir.resolve((groovy.json.JsonSlurper().parse(index) as Map<*, *>)[key] as String)
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
    val fakeTime = fakeTimePath.get().incoming.files
    val version = versionName

    // The argfile is these files, these strings and these directories and nothing else. All of them
    // are declared, so a newer Slime Launcher rewrites it instead of leaving yesterday's command
    // behind an up-to-date check. The heap is not in it: it changes nothing a dump contains, and
    // every dump takes the argfile as an input, so a different -Pmonifactory.heap would redo them.
    inputs.files(filesJson, vanillaJson, vanillaLibList, forgeJson, slimeJars, fakeGlfw, fakeGlfwCompanions, fakeTime)
    inputs.property("versionName", version)
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
            // On -cp as well as -javaagent, so BootstrapLauncher makes a module of it in MC-BOOTSTRAP. That copy is
            // the clock: every layer above reads it, so the game's classes and the renderer mod all link to it.
            addAll(fakeTime)
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
            // java.library.path stays unset on purpose - it is what wakes Slime Launcher's own
            // native extraction, and LWJGL 3 unpacks itself.
            add("-Dorg.lwjgl.system.SharedLibraryExtractPath=${natives.absolutePath}")
            add("-Djna.tmpdir=${natives.absolutePath}")
            add("-Dio.netty.native.workdir=${natives.absolutePath}")
            // The fake clock. The agent itself runs off the app class path; see the -cp entry for where the clock is.
            add("-javaagent:" + fakeTime.singleFile.absolutePath)
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

/** Where runGame has the renderer mod write. */
val renderDir = layout.buildDirectory.dir("render")

/**
 * Boots the real Monifactory install with the renderer mod in [mode], writing into [output], which is emptied first.
 * The game exits on its own once the renderer is done. Anything added to args after this goes after the argfile's main
 * class, so it reaches the game rather than the JVM.
 */
fun Exec.bootRenderer(mode: String, output: Provider<Directory>, vararg properties: Pair<String, String>) {
    dependsOn(writeLaunchArgs, installPack)

    val instance = instanceDir.get().asFile
    val natives = nativesDir.get().asFile
    // Lazy, since an artifact directory is named after the pack, and that is not known until the pack is downloaded.
    val out = output.map { it.asFile }
    doFirst {
        instance.mkdirs()
        natives.mkdirs()
        // Every run writes a whole set; a file left over from the last one would pass for part of this one.
        out.get().deleteRecursively()
    }
    doFirst(HeadlessInstance(instance))

    workingDir = instance
    executable = javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.absolutePath
    // Before the argfile, since everything after its main class is an argument to the game. Plain args always come
    // before the argument providers, and the providers keep their order.
    args("-Dmonifactory.dumper.mode=$mode")
    properties.forEach { (key, value) -> args("-Dmonifactory.dumper.$key=$value") }
    argumentProviders.add(SystemProperty("monifactory.dumper.output", out))
    // Monifactory does not finish loading in a default heap. Here rather than in the argfile, see writeLaunchArgs.
    argumentProviders.add(Heap(maxHeap, gameJvmFlags))
    argumentProviders.add(ArgFile(launchArgs))
    val heapWarning = GameMemory.heapWarning(maxHeap)
    if (heapWarning != null) doFirst { logger.warn(heapWarning) }

    // The fake GLFW never talks to a display server, so the game gets none. Anything in the pack
    // that still reaches for one - AWT, tinyfd - fails where it can be seen instead of opening a
    // window on whoever's desktop the build happens to run.
    environment.remove("DISPLAY")
    environment.remove("WAYLAND_DISPLAY")
    environment(gameEnvironment)
}

/** What runGame makes: sample (the default), census, seq, dump or data. See Dumper.Mode. */
val runMode = providers.gradleProperty("monifactory.mode").getOrElse("sample")
/** How many recipes runGame draws, and the seed that picks them. The mod defaults to 1 (4,000 for a census) and 0. */
val sampleCount = providers.gradleProperty("monifactory.sample.count")
val sampleSeed = providers.gradleProperty("monifactory.sample.seed")
/**
 * Any other renderer setting, as -Pmonifactory.dumper=key=value[,key=value...], each passed on as
 * -Dmonifactory.dumper.key=value: encoders, encodeBufferMiB, every, sample, checkPipeline, checkSync.
 */
val rendererSettings = providers.gradleProperty("monifactory.dumper").map { settings ->
    settings.split(",").filter { it.isNotBlank() }.map {
        val (key, value) = it.split("=", limit = 2).also { pair ->
            if (pair.size != 2) throw GradleException("-Pmonifactory.dumper wants key=value pairs, got '$it'")
        }
        key.trim() to value.trim()
    }
}.getOrElse(emptyList())

/**
 * How many games the full build runs at once (-Pmonifactory.processes=N). Each renders the
 * recipes whose stable key hashes to its index into an artifact of its own, and a merge makes the one artifact of
 * them. See [ShardedGames] and MergeShards. The render thread is one game's floor, so N games take about 1/N of the time.
 */
val processes = providers.gradleProperty("monifactory.processes").map { it.trim().toInt() }.getOrElse(1).also {
    if (it < 1) throw GradleException("-Pmonifactory.processes must be at least 1, not $it")
}

val runGame = tasks.register<Exec>("runGame") {
    group = "modpack"
    description = "Boots the real Monifactory install, which renders into build/render and exits. Assets download on the first run."

    // A seeded sample of the corpus instead of one recipe, e.g. -Pmonifactory.sample.count=2000 to render the same
    // recipes in two setups and diff them pixel for pixel, -Pmonifactory.mode=census for the animation census, or
    // -Pmonifactory.mode=seq for the raw hash sequences :dumper:compare:checkDetection checks the detection rules on.
    // The same seed picks the same recipes in any boot.
    bootRenderer(
        runMode,
        renderDir,
        *(listOfNotNull(sampleCount.orNull?.let { "count" to it }, sampleSeed.orNull?.let { "seed" to it })
            + rendererSettings).toTypedArray(),
    )
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

/** A -D argument whose value is only known when the task runs. */
class SystemProperty(private val key: String, private val value: Provider<File>) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-D$key=" + value.get().absolutePath)
}

/**
 * The game's -Xmx and [gameJvmFlags]. A provider, not plain arguments: Exec's args are inputs, and neither changes
 * anything a dump contains, so asking for a different heap must not redo a finished dump. A provider is an input only
 * through its annotated properties, and this one has none.
 */
class Heap(private val size: String, private val flags: List<String>) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Xmx$size") + flags
}

/** MergeShards' command line: the artifact to write and every game's directory, which are named by index. */
class MergeArguments(
    private val out: Provider<File>, private val shards: Provider<File>, private val count: Int,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("--out", out.get().absolutePath) +
        (0 until count).flatMap { listOf("--shard", shards.get().resolve("$it").absolutePath) }
}

/** Kept out of the task body so the configuration cache has a class to serialize, not a script. */
class ArgFile(private val file: Provider<RegularFile>) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("@" + file.get().asFile.absolutePath)
}


/**
 * The pack's own name for itself, "<name>-<version>" out of the manifest.json the download checked against the pins,
 * e.g. Monifactory-0.13.8. Read when a task runs, since the pack is downloaded by then and not before. It goes through
 * the files' elements rather than the configuration itself: that provider knows downloadPack produces it, so a task
 * that takes it as an input waits for the download instead of reading the manifest while the task graph is built.
 */
val packName: Provider<String> = pack.flatMap { it.incoming.files.elements }.map { elements ->
    elements.single().asFile.resolve("manifest.json")
}.map { manifest ->
    val json = groovy.json.JsonSlurper().parse(manifest) as Map<*, *>
    // It becomes a directory name, so nothing in it may be a path separator or confuse a shell.
    "${json["name"]}-${json["version"]}".replace(Regex("[^A-Za-z0-9._+-]"), "_")
}

/**
 * The artifact directory, one per pack version. A new pack version writes a new directory and the old one stays valid
 * until it is deleted; only a rebuild of the same version replaces its own.
 */
val dumpsDir = layout.buildDirectory.dir("dumps")
val artifactDir = dumpsDir.zip(packName) { dumps, name -> dumps.dir(name) }
val latestDump = dumpsDir.get().dir("latest")

// What the artifact is a function of: the renderer, the pack and the launch. The instance directory itself is not an
// input, since the game writes logs and options into it on every boot.
fun Task.dumpInputs() {
    inputs.files(tasks.named("renameJar")).withPropertyName("renderer")
    inputs.files(pack.get().incoming.files).withPropertyName("pack")
    inputs.files(writeLaunchArgs).withPropertyName("launch")
    inputs.property("settings", rendererSettings.joinToString(",") { (key, value) -> "$key=$value" })
    // One boot in eight has been seen to hang in mod construction, before the renderer exists to notice. A boot is
    // about 2.5 minutes and EMI's reload half a minute; the first run also downloads 650 MB of assets. The batch is
    // then hours: every animated recipe is drawn until its loop closes or 400 frames go by, and about half of them
    // never close. The limit is there for a boot that never gets going, not to bound the batch.
    timeout = Duration.ofHours(12)
}

/**
 * Fails the task unless the game left a finished artifact in [artifact]. The renderer writes meta.json last, after
 * recipes.json and the stills, so its absence means the run ended early. A clean exit is not proof: when the heap runs
 * out, Minecraft's own out-of-memory handling stops the game and the JVM still exits 0 (seen with a 3G heap during
 * EMI's reload), and without this the build would report success with no artifact at all.
 */
fun Exec.requireArtifact(artifact: Provider<Directory>, images: Boolean = true) {
    val dir = artifact.map { it.asFile }
    val log = instanceDir.get().asFile.resolve("logs/latest.log")
    val files = listOfNotNull("recipes.json", "stills.pak".takeIf { images }, "stills.json".takeIf { images },
        "meta.json")
    doLast {
        val missing = files.filterNot { dir.get().resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "The game exited without finishing the artifact: ${missing.joinToString()} missing in ${dir.get()}. "
                    + "The reason is in $log (look for OutOfMemoryError or the last [dumper] line)."
            )
        }
    }
}

/** Where the games of a sharded build write, a directory per task and in it one per game, before the merge. */
val shardsDir = layout.buildDirectory.dir("shards")
/** The instance directories of a sharded build's games but the first, which runs in [instanceDir]. */
val shardInstancesDir = layout.buildDirectory.dir("instances")

/**
 * Registers [name], which writes the full artifact into [output]. With one process it is the game itself, as an Exec;
 * with more, `<name>Shards` runs the games and [name] merges what they wrote. [games] configures whichever task runs
 * games, [finish] whichever writes [output]; with one process that is the same task.
 */
fun registerDump(
    name: String, output: Provider<Directory>, describe: String,
    games: Task.() -> Unit = {}, finish: Task.() -> Unit = {},
): TaskProvider<out Task> {
    if (processes == 1) return tasks.register<Exec>(name) {
        group = "modpack"
        description = describe
        bootRenderer("dump", output, *rendererSettings.toTypedArray())
        dumpInputs()
        outputs.dir(output)
        requireArtifact(output)
        games()
        finish()
    }

    // Named after the task, not the pack: the pack's name is not known until it is downloaded, and the merge's inputs
    // are resolved before that.
    val shards = shardsDir.map { it.dir(name) }
    val sharded = tasks.register<ShardedGames>(name + "Shards") {
        group = "modpack"
        description = "Runs $processes games at once, each rendering its share of the recipes into build/shards/$name."
        dependsOn(writeLaunchArgs, installPack)
        dumpInputs()
        count = processes
        settings = rendererSettings.map { (key, value) -> "$key=$value" }
        heap = maxHeap
        jvmFlags = gameJvmFlags
        environment = gameEnvironment
        javaExecutable = javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.absolutePath
        argFile = launchArgs
        instance = instanceDir
        instances = shardInstancesDir
        natives = nativesDir
        this.shards = shards
        doFirst(HeadlessInstance(instanceDir.get().asFile))
        games()
    }
    return tasks.register<JavaExec>(name) {
        group = "modpack"
        description = describe
        classpath = compareToolPath.get()
        mainClass = "com.iluha168.monifactory.compare.MergeShards"
        javaLauncher = javaToolchains.launcherFor(java.toolchain)
        // Every shard's recipes.json at once, as text, and a digest per still.
        maxHeapSize = "4G"
        inputs.files(sharded).withPropertyName("shards")
        outputs.dir(output)
        val out = output.map { it.asFile }
        argumentProviders.add(MergeArguments(out, shards.map { it.asFile }, processes))
        doFirst { out.get().deleteRecursively() }
        finish()
    }
}

val dump = registerDump(
    "dump", artifactDir,
    "Boots the pack and writes the artifact directory, build/dumps/<pack>-<version>: recipes.json, stills.pak, stills.json and meta.json.",
    finish = {
        // build/dumps/latest, a relative symlink to the artifact this task last finished. It is what dumpArtifact hands
        // to other projects: Gradle wants an artifact's path while it resolves the dependency graph, which is before the
        // download has said which pack version this is.
        val latest = latestDump.asFile.toPath()
        val target = artifactDir.map { it.asFile.name }
        doLast {
            Files.deleteIfExists(latest)
            Files.createSymbolicLink(latest, Path.of(target.get()))
        }
    },
)

/**
 * The data-only artifact, recipes.json without images, in a directory of its own so it never replaces the full build's
 * or moves `latest`, which other projects read images through.
 */
val dataArtifactDir = dumpsDir.zip(packName) { dumps, name -> dumps.dir("$name-data") }
val latestDataDump = dumpsDir.get().dir("latest-data")

val dumpData = tasks.register<Exec>("dumpData") {
    group = "modpack"
    description = "Boots the pack and writes recipes.json, categories.tsv and meta.json without rendering anything, into build/dumps/<pack>-<version>-data."

    bootRenderer("data", dataArtifactDir, *rendererSettings.toTypedArray())
    dumpInputs()
    outputs.dir(dataArtifactDir)
    requireArtifact(dataArtifactDir, images = false)
    // After the full build, never beside it: two games at once would share the instance directory.
    mustRunAfter(dump)

    // build/dumps/latest-data, what dumpDataArtifact hands to other projects, for the same reason dump keeps latest.
    val latest = latestDataDump.asFile.toPath()
    val target = dataArtifactDir.map { it.asFile.name }
    doLast {
        Files.deleteIfExists(latest)
        Files.createSymbolicLink(latest, Path.of(target.get()))
    }
}

// The checks are the :dumper:compare tools, run on their own classpath. It carries webp-imageio's decoder half and its
// Kotlin, which is fine out here: they are plain JVM programs, not the game.
val compareToolScope = configurations.dependencyScope("compareTool")
val compareToolPath = configurations.resolvable("compareToolPath") {
    extendsFrom(compareToolScope.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    compareToolScope.name(project(":dumper:compare"))
}

/**
 * Runs VerifyArtifact over [artifact]: every recipes.json picture must be drawable from the artifact's stills, each of
 * which decodes, or, for a data-only artifact (meta.json says `"images": false`), every entry must parse and have no
 * image.
 */
fun JavaExec.verify(artifact: Provider<Directory>) {
    group = "verification"
    classpath = compareToolPath.get()
    mainClass = "com.iluha168.monifactory.compare.VerifyArtifact"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    maxHeapSize = "2G"

    val dir = artifact.map { it.asFile }
    inputs.files(artifact).withPropertyName("artifact")
    // Up to date for as long as the artifact is. A marker, since the check itself writes nothing.
    val marker = layout.buildDirectory.file("tmp/$name/verified")
    outputs.file(marker)
    argumentProviders.add(CommandLineArgumentProvider { listOf("--artifact", dir.get().absolutePath) })
    // A dump that stopped before writing recipes.json has nothing to check, and its own failure says why.
    onlyIf("the artifact has a recipes.json") { dir.get().resolve("recipes.json").isFile }
    val markerFile = marker.map { it.asFile }
    doLast { markerFile.get().writeText("") }
}

val verifyDump = tasks.register<JavaExec>("verifyDump") {
    description = "Checks that every recipes.json picture of the artifact can be drawn from its decodable stills."
    verify(artifactDir)
}
dump.configure { finalizedBy(verifyDump) }

val verifyDumpData = tasks.register<JavaExec>("verifyDumpData") {
    description = "Checks that the data-only artifact's recipes.json parses whole and that no entry claims an image."
    verify(dataArtifactDir)
}
dumpData.configure { finalizedBy(verifyDumpData) }

/** Where the rebuild check puts its second build of the same pack version. */
val rebuildDir = dumpsDir.zip(packName) { dumps, name -> dumps.dir("$name-rebuild") }

val rebuild = registerDump(
    "rebuild", rebuildDir,
    "Builds the artifact of the same pack version a second time, into build/dumps/<pack>-<version>-rebuild.",
    games = {
        // Its whole point is to boot again.
        outputs.upToDateWhen { false }
        // After the first build, never beside it: two games at once would share the instance directory.
        mustRunAfter(dump)
    },
    finish = { outputs.upToDateWhen { false } },
)

val verifyRebuild = tasks.register<JavaExec>("verifyRebuild") {
    description = "Checks that every recipes.json picture of the rebuild can be drawn from its decodable stills."
    verify(rebuildDir)
}
rebuild.configure { finalizedBy(verifyRebuild) }

/**
 * The same pack version built twice must agree as sets, recipe for recipe, and any difference must be
 * a documented one: GregTech's flicker, the TMRV/info drift, or a pixel change inside one boot-varying slot. Byte
 * identity is not the bar, because the pack does not boot the same way twice. README.md's "Checking a rebuild" has
 * the rules.
 */
tasks.register<JavaExec>("rebuildCheck") {
    group = "verification"
    description = "Builds the current pack version twice and compares the two artifacts as sets."
    dependsOn(dump, rebuild, verifyDump, verifyRebuild)

    classpath = compareToolPath.get()
    mainClass = "com.iluha168.monifactory.compare.CompareDumps"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // A 90 MB recipes.json twice over, as objects.
    maxHeapSize = "4G"

    val first = artifactDir.map { it.asFile }
    val second = rebuildDir.map { it.asFile }
    val report = layout.buildDirectory.file("rebuild-compare.tsv").map { it.asFile }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--a", first.get().absolutePath, "--b", second.get().absolutePath, "--out", report.get().absolutePath)
    })
    outputs.upToDateWhen { false }
}

val dumpArtifact = configurations.consumable("dumpArtifact")
val dumpDataArtifact = configurations.consumable("dumpDataArtifact")

artifacts {
    add(dumpArtifact.name, latestDump) {
        builtBy(dump)
    }
    add(dumpDataArtifact.name, latestDataDump) {
        builtBy(dumpData)
    }
}

/**
 * META-INF/jarjar/metadata.json: each nested jar's coordinates, the version it is, and the range the renderer accepts,
 * which is that version or newer. Forge picks one copy per coordinate across every mod that nests it.
 */
abstract class JarJarMetadata : DefaultTask() {
    /** group:artifact:version:file per nested jar. */
    @get:Input
    abstract val jars: ListProperty<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val entries = jars.get().sorted().map {
            val (group, artifact, version, file) = it.split(":")
            mapOf(
                "identifier" to mapOf("group" to group, "artifact" to artifact),
                "version" to mapOf("range" to "[$version,)", "artifactVersion" to version),
                "path" to "META-INF/jarjar/$file",
                "isObfuscated" to false,
            )
        }
        output.get().asFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf("jars" to entries))) + "\n")
    }
}

/**
 * The games of a sharded build, -Pmonifactory.processes=N: N at once, game k rendering the recipes whose stable key
 * hashes to k into [shards]/k. MergeShards makes the artifact of them.
 *
 * Game 0 runs in the instance directory itself. Every other game gets one of its own under [instances], since a boot
 * writes into its instance: logs, journeymap/, local/, fancymenu_data/, and nearly every file in config/, rewritten
 * with the same text each boot (a few, such as config/oculus.properties, with a new timestamp in it). Two games writing
 * one file at once could each read the other's half-written copy. So each extra instance is a fresh copy of game 0's
 * (about 80 MB, a second or two), made before any game starts, except for what no boot writes: mods/, resourcepacks/
 * and shaderpacks/ are symlinks to game 0's, which is where the 260 MB are. logs/ is not copied; each game keeps its
 * own. The natives directory is per game too: LWJGL writes its libraries there when they are missing, and two games
 * unpacking the same file at once could load a half-written one. The game's assets, libraries and Forge install are
 * read only.
 *
 * The games start one after another, not together. Game k+1 starts once game k logs that the renderer released the
 * server side ([RELEASED]), about two minutes into its boot: the boot's memory peak (the datapack reload and EMI's
 * reload) comes before that line, so no two games are ever at their peak at once. A game that fails or hangs before the
 * line holds the next one back only until it exits or [SILENCE] runs out.
 *
 * A game that fails is started once more, on its own, while the others carry on; a second failure fails the build.
 * Failing is: a non-zero exit (1 for the renderer's own failures, such as the server datapack reload that hangs about
 * one boot in eight; 143 or 137 when an out-of-memory killer took it), an exit without a finished artifact, or
 * [SILENCE] without a new line of output, which is a boot hung before the renderer exists to notice (its reload
 * timeouts are 10 and 15 minutes, and a running batch logs every minute). Each failed attempt's logs/latest.log is kept
 * next to the games' directories, since the next boot rolls it away.
 */
abstract class ShardedGames : DefaultTask() {
    @get:Input
    abstract val count: Property<Int>

    /** -Pmonifactory.dumper's key=value pairs. An input through dumpInputs already. */
    @get:Internal
    abstract val settings: ListProperty<String>

    /** Each game's -Xmx. Not an input, as for the single game: it changes nothing the artifact holds. */
    @get:Internal
    abstract val heap: Property<String>

    /** JVM flags every game gets after its -Xmx; nor are these. */
    @get:Internal
    abstract val jvmFlags: ListProperty<String>

    /** Added to each game's environment. */
    @get:Internal
    abstract val environment: MapProperty<String, String>

    @get:Internal
    abstract val javaExecutable: Property<String>

    @get:Internal
    abstract val argFile: RegularFileProperty

    @get:Internal
    abstract val instance: DirectoryProperty

    @get:Internal
    abstract val instances: DirectoryProperty

    @get:Internal
    abstract val natives: DirectoryProperty

    @get:OutputDirectory
    abstract val shards: DirectoryProperty

    companion object {
        val SILENCE: Duration = Duration.ofMinutes(30)
        private val PROGRESS: Duration = Duration.ofMinutes(5)
        /** What no boot writes, so the extra instances link to game 0's instead of copying it. */
        private val SHARED = setOf("mods", "resourcepacks", "shaderpacks")
        /** What each instance keeps for itself. */
        private val OWN = setOf("logs")
        /** The artifact files a game writes, meta.json last. */
        private val FILES = listOf("recipes.json", "stills.pak", "stills.json", "render.tsv", "shard.tsv", "meta.json")
        /** ServerLeftovers.MARKER in the renderer, which logs it once the boot's memory peak is over. */
        const val RELEASED = "[dumper] released the server side"
        private val RELEASED_HEAP = Regex("""heap (\d+) MiB used after GC""")
    }

    private inner class Game(val index: Int, val instance: File, val args: File, val artifact: File) {
        var attempt = 0
        var process: Process? = null
        /** The game's output, this attempt's. */
        var log: File? = null
        var killed: String? = null
        var lastSize = -1L
        var lastChange = 0L
        var started = 0L
        var done = false
        /** Waiting for its turn to start: at first, and again after a first failure. */
        var pending = true
        /** Whether this attempt has logged [RELEASED], and how far into its output the search for it got. */
        var released = false
        private var scanned = 0L
        private var tail = ""
        val failures = mutableListOf<String>()

        val running get() = process != null
        /** Started and not yet past its boot's memory peak. The next game waits while one is. */
        val booting get() = running && !released
        /** Failed twice: the build fails whatever the other games do. */
        val lost get() = !done && process == null && failures.size >= 2

        fun start() {
            attempt++
            pending = false
            released = false
            scanned = 0L
            tail = ""
            killed = null
            artifact.deleteRecursively()
            val log = shards.get().asFile.resolve("$index-attempt$attempt.out")
            this.log = log
            val command = buildList {
                add(javaExecutable.get())
                add("-Dmonifactory.dumper.mode=dump")
                settings.get().forEach { add("-Dmonifactory.dumper.$it") }
                add("-Dmonifactory.dumper.shards=${count.get()}")
                add("-Dmonifactory.dumper.shard=$index")
                add("-Dmonifactory.dumper.output=${artifact.absolutePath}")
                add("-Xmx${heap.get()}")
                addAll(jvmFlags.get())
                add("@${args.absolutePath}")
            }
            val builder = ProcessBuilder(command).directory(instance).redirectErrorStream(true)
                .redirectOutput(log)
            // As for the single game: no display server, so nothing in the pack can open a window.
            builder.environment().remove("DISPLAY")
            builder.environment().remove("WAYLAND_DISPLAY")
            builder.environment().putAll(environment.get())
            process = builder.start()
            started = System.nanoTime()
            lastChange = started
            lastSize = -1
            logger.lifecycle("game $index: attempt $attempt started in $instance, output in $log")
        }

        fun poll(now: Long) {
            val running = process ?: return
            if (running.isAlive) {
                val size = log!!.length()
                if (size != lastSize) {
                    lastSize = size
                    lastChange = now
                    if (!released) scanForRelease(now)
                } else if (killed == null && now - lastChange > SILENCE.toNanos()) {
                    killed = "wrote nothing for ${SILENCE.toMinutes()} minutes"
                    logger.warn("game $index $killed; stopping it")
                    kill()
                }
                return
            }
            process = null
            val exit = running.exitValue()
            val minutes = (now - started) / 60_000_000_000L
            val missing = FILES.filterNot { artifact.resolve(it).isFile }
            if (killed == null && exit == 0 && missing.isEmpty()) {
                done = true
                logger.lifecycle("game $index: done in $minutes min")
                return
            }
            val reason = killed ?: when {
                exit == 143 || exit == 137 -> "was killed ($exit), most likely by an out-of-memory killer"
                exit != 0 -> "exited with $exit"
                else -> "exited without finishing its artifact: ${missing.joinToString()} missing"
            }
            // The next boot in this instance rolls latest.log away.
            val saved = shards.get().asFile.resolve("$index-attempt$attempt.latest.log")
            val latest = instance.resolve("logs/latest.log")
            if (latest.isFile) latest.copyTo(saved, overwrite = true)
            failures += "game $index, attempt $attempt, $reason after $minutes min: see $saved"
            if (attempt < 2) {
                logger.warn("game $index $reason after $minutes min; its log is $saved. It starts again once no "
                    + "other game is booting.")
                pending = true
            }
        }

        /** Reads what the game wrote since the last look for [RELEASED]. A line may be split between two looks. */
        private fun scanForRelease(now: Long) {
            val log = log ?: return
            val text = RandomAccessFile(log, "r").use { file ->
                val end = file.length()
                if (end <= scanned) return
                val bytes = ByteArray((end - scanned).toInt())
                file.seek(scanned)
                file.readFully(bytes)
                scanned = end
                // The marker is ASCII, and Latin-1 never fails on a multi-byte character cut in half.
                tail + String(bytes, Charsets.ISO_8859_1)
            }
            val at = text.indexOf(RELEASED)
            if (at < 0) {
                tail = text.takeLast(RELEASED.length + 64)
                return
            }
            released = true
            val heap = RELEASED_HEAP.find(text, at)?.groupValues?.get(1)?.let { ", heap $it MiB after GC" } ?: ""
            logger.lifecycle("game $index: released the server side ${(now - started) / 1_000_000_000L} s after "
                + "it started$heap; its boot is over")
        }

        fun kill() {
            process?.let { running ->
                running.descendants().forEach { it.destroyForcibly() }
                running.destroyForcibly()
            }
        }

        /** The last [dumper] line of the game's output, for the progress report. */
        fun lastDumperLine(): String? {
            val log = log ?: return null
            if (!log.isFile) return null
            RandomAccessFile(log, "r").use { file ->
                val start = maxOf(0L, file.length() - 65536)
                val bytes = ByteArray((file.length() - start).toInt())
                file.seek(start)
                file.readFully(bytes)
                return String(bytes, Charsets.UTF_8).lineSequence().lastOrNull { "[dumper]" in it }
                    ?.substringAfter("[dumper] ")?.take(300)
            }
        }
    }

    @TaskAction
    fun run() {
        val n = count.get()
        val every = settings.get().map { it.split("=", limit = 2) }.lastOrNull { it[0] == "every" }?.get(1)
        if (every != null && every.trim() != "1") {
            throw GradleException(
                "every=$every picks every Nth recipe by its place in EMI's list, and that list differs by a few "
                    + "dozen recipes from boot to boot, so $n games would count from different places. Use "
                    + "sample=$every: it picks by what a recipe is, which every game agrees on."
            )
        }
        GameMemory.heapWarning(heap.get())?.let { logger.warn(it) }
        warnAboutMemory(n)

        val root = shards.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        val source = instance.get().asFile
        val games = (0 until n).map { k ->
            if (k == 0) {
                Game(0, source, argFile.get().asFile, root.resolve("0"))
            } else {
                val own = instances.get().asFile.resolve("$k")
                val ownNatives = instances.get().asFile.resolve("$k-natives")
                val prepared = System.nanoTime()
                prepare(source, own)
                ownNatives.mkdirs()
                val args = instances.get().asFile.resolve("$k.args")
                args.writeText(retarget(argFile.get().asFile.readText(), source, own, ownNatives))
                logger.info("instance $own prepared in ${(System.nanoTime() - prepared) / 1_000_000} ms")
                Game(k, own, args, root.resolve("$k"))
            }
        }

        logger.lifecycle("running $n games, -Xmx${heap.get()} each, each started once the one before it is past its "
            + "boot; game 0 in $source, the others in ${instances.get().asFile}; their artifacts go to $root")
        try {
            var lastProgress = System.nanoTime()
            // A game that failed twice fails the build, so the others stop then rather than hours later.
            while (games.any { it.running || it.pending } && games.none { it.lost }) {
                // One boot at a time, a retry included.
                if (games.none { it.booting }) games.firstOrNull { it.pending }?.start()
                Thread.sleep(2000)
                val now = System.nanoTime()
                games.forEach { it.poll(now) }
                if (now - lastProgress > PROGRESS.toNanos()) {
                    lastProgress = now
                    games.forEach { game ->
                        when {
                            game.running ->
                                logger.lifecycle("game ${game.index}: ${game.lastDumperLine() ?: "booting"}")
                            game.pending -> logger.lifecycle("game ${game.index}: waiting for a boot to finish")
                        }
                    }
                }
            }
        } finally {
            // Cancelled or timed out: no game outlives the build.
            games.forEach { it.kill() }
        }

        val lost = games.filter { it.lost }
        if (lost.isNotEmpty()) {
            val stopped = games.filter { !it.done && !it.lost }.map { it.index }
            throw GradleException("${lost.size} of $n games failed twice"
                + (if (stopped.isEmpty()) "" else ", so games $stopped were stopped or never started") + ":\n  "
                + lost.flatMap { it.failures }.joinToString("\n  "))
        }
        games.filter { it.failures.isNotEmpty() }.forEach { game ->
            logger.warn("game ${game.index} needed a second attempt: ${game.failures.joinToString("; ")}")
        }
    }

    /**
     * Makes [target] a copy of [source] for a game of its own: [SHARED] as symlinks, [OWN] left as it is, everything
     * else copied fresh. Anything there from the last run is removed first, so the copy is what [source] holds now.
     * Never follows a symlink while deleting, or the mods would go with it.
     */
    private fun prepare(source: File, target: File) {
        target.mkdirs()
        target.listFiles()!!.filter { it.name !in OWN }.forEach { deleteTree(it.toPath()) }
        source.listFiles()!!.filter { it.name !in OWN }.forEach { entry ->
            val to = target.toPath().resolve(entry.name)
            if (entry.name in SHARED) Files.createSymbolicLink(to, entry.toPath().toAbsolutePath())
            else copyTree(entry.toPath(), to)
        }
    }

    private fun deleteTree(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(path).use { children -> children.toList() }.forEach(::deleteTree)
        }
        Files.delete(path)
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.forEach { path ->
                val copy = to.resolve(from.relativize(path).toString())
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(copy)
                else Files.copy(path, copy, StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS)
            }
        }
    }

    /** The argfile with game 0's instance and natives directories swapped for another game's. */
    private fun retarget(args: String, source: File, instance: File, natives: File): String {
        // As writeLaunchArgs quotes them.
        fun quoted(file: File) = file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        val game = "\"" + quoted(source) + "\""
        val nativesPath = quoted(this.natives.get().asFile) + "\""
        if (game !in args || nativesPath !in args) {
            throw GradleException("${argFile.get().asFile} names neither $source nor ${this.natives.get().asFile}")
        }
        return args.replace(game, "\"" + quoted(instance) + "\"").replace(nativesPath, quoted(natives) + "\"")
    }

    /**
     * Warns when the games may not fit in memory: all but one of them rendering and one at its boot's peak, which is
     * the most the staggered start lets happen at once ([GameMemory]: about 7.5 and 8 GB at -Xmx5G), and 2 GB to spare. What happens when
     * they do not fit was seen on a laptop with 10 GB free: without swap, earlyoom killed the games (exit 143) within
     * minutes; with swap, both games slowed to a crawl and the NVIDIA driver failed to map GPU memory, which left the
     * GPU needing a reboot.
     */
    private fun warnAboutMemory(n: Int) {
        val meminfo = File("/proc/meminfo")
        if (!meminfo.isFile) return
        fun kib(key: String) = meminfo.readLines().firstOrNull { it.startsWith("$key:") }
            ?.let { Regex("""\d+""").find(it)?.value?.toLong() }
        val total = kib("MemTotal") ?: return
        val available = (kib("MemAvailable") ?: total) shr 10
        val heapMib = GameMemory.mib(heap.get()) ?: return
        val rendering = heapMib + GameMemory.RENDER_OVERHEAD_MIB
        val booting = heapMib + GameMemory.BOOT_OVERHEAD_MIB
        val needed = (n - 1) * rendering + booting + GameMemory.HEADROOM_MIB
        if (needed > available) {
            logger.warn("$n games at -Xmx${heap.get()} want about %.1f GB (%.1f GB each rendering, one of them at its "
                .format(needed / 1024.0, rendering / 1024.0)
                + "boot's peak of %.1f GB, and 2 GB to spare), and %.1f of this machine's %.1f GB are available. "
                .format(booting / 1024.0, available / 1024.0, (total shr 10) / 1024.0)
                + "An out-of-memory killer may take a game, and swapping them has taken the GPU driver down with it; "
                + "close programs, or use a smaller -Pmonifactory.processes.")
        }
    }
}
