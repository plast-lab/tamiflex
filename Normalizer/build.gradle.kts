val asmVersion: String by rootProject.extra

dependencies {
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-commons:$asmVersion")
}

// Shared by the agents, which must load on JVMs 8-25.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}
