plugins {
    java
}

group = property("group").toString()
version = property("version").toString()

val javaVersion = property("java_version").toString().toInt()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

repositories {
    mavenCentral()
}

dependencies {
    // Provided by the server at runtime; never bundled into our jar.
    compileOnly(files(rootDir.resolve("libs/HytaleServer.jar")))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaVersion)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("manifest.json") { expand(props) }
}

val modName = property("mod_name").toString()

tasks.named<Jar>("jar") {
    archiveBaseName.set(modName)
}

val hytaleModsDir = providers.gradleProperty("hytaleModsDir")

tasks.register<Copy>("deployPlugin") {
    group = "hytale"
    description = "Copies the built plugin jar into the directory given by the hytaleModsDir property."
    dependsOn(tasks.named("jar"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    into(hytaleModsDir.orElse("__hytaleModsDir_not_set__"))

    val modsDir = hytaleModsDir
    val jarName = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    doFirst {
        if (!modsDir.isPresent || modsDir.get().isBlank()) {
            throw GradleException(
                "hytaleModsDir is not set. Pass -PhytaleModsDir=\"<path to Hytale UserData/Mods>\" " +
                    "or add hytaleModsDir=<path> to ~/.gradle/gradle.properties."
            )
        }
        val dir = File(modsDir.get())
        if (!dir.isDirectory) {
            throw GradleException("hytaleModsDir does not exist or is not a directory: $dir")
        }
    }
    doLast {
        logger.lifecycle("Deployed ${jarName.get()} to ${modsDir.get()}")
    }
}
