// Root build for the modernized TamiFlex.
//
// Build toolchain is JDK 25 everywhere (goal a). Individual modules pick their
// bytecode target via `options.release`:
//   * agents + normalizer  -> release 8  (goal c: the agent jars must load on JVMs 8-25)
//   * booster + database   -> release 25 (plain CLIs, no reason to stay on 8)
// ASM 9.8 is the first ASM that understands Java 25 (class-file major 69).

val asmVersion by extra("9.8")
val sootVersion by extra("4.6.0")
val mysqlVersion by extra("9.3.0")

allprojects {
    group = "de.bodden.tamiflex"
    version = "trunk"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Historical source used raw types etc.; keep the build readable.
        options.compilerArgs.add("-Xlint:-options")
    }

    // The old Ant build kept sources in `src` (+ `rtlib` for the injected runtime
    // classes) rather than the Maven/Gradle default. Point Gradle at them without
    // moving files.
    extensions.configure<SourceSetContainer> {
        named("main") {
            val dirs = mutableListOf("src")
            if (file("rtlib").isDirectory) dirs.add("rtlib")
            java.setSrcDirs(dirs)
            resources.setSrcDirs(emptyList<String>())
        }
        named("test") {
            java.setSrcDirs(emptyList<String>())
            resources.setSrcDirs(emptyList<String>())
        }
    }
}
