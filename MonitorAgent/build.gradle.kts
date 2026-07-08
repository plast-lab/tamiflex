val asmVersion: String by rootProject.extra

dependencies {
    implementation(project(":normalizer"))
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-commons:$asmVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

// The monitor agent is the older sibling of the play-out agent; it shares the
// `de.bodden.tamiflex.playout.Agent` premain class, so it gets a distinct jar
// name to avoid colliding with `poa.jar`.
tasks.jar {
    archiveFileName.set("poa-monitor.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "de.bodden.tamiflex.playout.Agent",
            "Can-Retransform-Classes" to "true",
            "Implementation-Version" to project.version,
        )
    }
    from(configurations.runtimeClasspath.map { cfg -> cfg.map { if (it.isDirectory) it else zipTree(it) } })
}
