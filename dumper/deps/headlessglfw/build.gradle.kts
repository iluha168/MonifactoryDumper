plugins {
    `java-library`
}

group = "com.iluha168.monifactory"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
    // The module this compiles into is not a module of its own - see compileJava below.
    modularity.inferModulePath = false
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// None of these are ever transitive: every LWJGL jar but the EGL binding is already on the game's
// classpath, and a second copy of org.lwjgl from here would be a duplicate module at boot.
val lwjglModulesScope = configurations.dependencyScope("lwjglModules")
val mergedWithScope = configurations.dependencyScope("mergedWith")

val lwjglModules = configurations.resolvable("lwjglModulesPath") { extendsFrom(lwjglModulesScope.get()) }
val mergedWith = configurations.resolvable("mergedWithPath") { extendsFrom(mergedWithScope.get()) }

dependencies {
    lwjglModulesScope.name(libs.lwjgl) { isTransitive = false }
    lwjglModulesScope.name(libs.lwjgl.opengl) { isTransitive = false }
    mergedWithScope.name(libs.lwjgl.glfw) { isTransitive = false }
    mergedWithScope.name(libs.lwjgl.egl) { isTransitive = false }

    // The only jar the game does not already have. It rides along to -cp and into the merge.
    runtimeOnly(libs.lwjgl.egl) { isTransitive = false }
}

// At boot, BootstrapLauncher's -DmergeModules unions the client's lwjgl-glfw jar, the EGL binding
// and this jar into one module, and this jar's module-info names it. The compile mirrors that:
// these sources are module org.lwjgl.glfw with the other two patched in, so everything the fake
// touches - GLFWVidMode from the real jar, org.lwjgl.egl - resolves exactly the way it will at runtime.
tasks.compileJava {
    classpath = files()
    options.compilerArgumentProviders.add(MergedModule(lwjglModules.get(), mergedWith.get()))
}

class MergedModule(
    @get:InputFiles @get:Classpath val modulePath: FileCollection,
    @get:InputFiles @get:Classpath val patch: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf(
        "--module-path", modulePath.asPath,
        "--patch-module", "org.lwjgl.glfw=" + patch.asPath,
    )
}
