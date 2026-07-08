val sootVersion: String by rootProject.extra

dependencies {
    // Modern Soot from Maven Central still ships the TamiFlex play-in machinery
    // (soot.jimple.toolkits.reflection.ReflectiveCallsInliner + soot.rtlib.tamiflex.*).
    implementation("org.soot-oss:soot:$sootVersion")
}

// Plain CLI: no reason to stay on the old bytecode level.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.jar {
    archiveFileName.set("booster.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "de.bodden.tamiflex.booster.ReflInliner",
            "Implementation-Version" to project.version,
        )
    }
    from(configurations.runtimeClasspath.map { cfg -> cfg.map { if (it.isDirectory) it else zipTree(it) } })
}
