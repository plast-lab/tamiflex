val mysqlVersion: String by rootProject.extra

dependencies {
    implementation("com.mysql:mysql-connector-j:$mysqlVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

// The play-out agent looks up `dbdumper-<version>.jar` next to itself to
// optionally dump refl.log into a database, so keep the versioned name.
tasks.jar {
    archiveFileName.set("dbdumper-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "de.bodden.tamiflex.db.DBMain",
            "Implementation-Version" to project.version,
        )
    }
    from(configurations.runtimeClasspath.map { cfg -> cfg.map { if (it.isDirectory) it else zipTree(it) } })
}
