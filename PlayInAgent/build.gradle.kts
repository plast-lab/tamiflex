val asmVersion: String by rootProject.extra

dependencies {
    implementation(project(":normalizer"))
    // ClassReplacer references org.objectweb.asm.ClassVisitor at compile time.
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-commons:$asmVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.jar {
    archiveFileName.set("pia.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "de.bodden.tamiflex.playin.Agent",
            "Main-Class" to "de.bodden.tamiflex.playin.Agent",
            "Can-Retransform-Classes" to "true",
            "Implementation-Version" to project.version,
        )
    }
    from("pia.properties")
    from(configurations.runtimeClasspath.map { cfg -> cfg.map { if (it.isDirectory) it else zipTree(it) } })
}
