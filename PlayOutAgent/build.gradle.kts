val asmVersion: String by rootProject.extra

dependencies {
    implementation(project(":normalizer"))
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-commons:$asmVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

// Fat agent jar: agent + injected rt classes + normalizer + ASM, all in one jar
// so that appendToBootstrapClassLoaderSearch(agentJar) exposes every class the
// instrumented java.base callers reference. Preserve the historical name `poa.jar`.
tasks.jar {
    archiveFileName.set("poa.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "de.bodden.tamiflex.playout.Agent",
            "Main-Class" to "de.bodden.tamiflex.playout.Agent",
            "Can-Retransform-Classes" to "true",
            "Implementation-Version" to project.version,
        )
    }
    from("poa.properties")
    from(configurations.runtimeClasspath.map { cfg -> cfg.map { if (it.isDirectory) it else zipTree(it) } })
}
