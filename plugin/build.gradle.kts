import java.util.Properties

plugins {
    java
}

group = property("group").toString()
version = property("version").toString()

val javaVersion = rootProject.property("java_version").toString().toInt()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

repositories {
    mavenCentral()
    maven {
        name = "Hytale"
        url = uri("https://maven.hytale.com/release")
    }
}

val hytaleServerVersion = property("hytale_server_version").toString()

dependencies {
    // Provided by the server at runtime; never bundled into our jar.
    compileOnly("com.hypixel.hytale:Server:$hytaleServerVersion")
    // Bundled into our jar (see tasks.jar) so the detached upload helper can run from it alone.
    implementation(project(":gdrive"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaVersion)
}

// ---- Google OAuth client ids, from the gitignored secrets.properties at the repo root ----

val secretsFile = rootProject.layout.projectDirectory.file("secrets.properties")
val generatedOAuthDir = layout.buildDirectory.dir("generated/oauth")

val generateOAuthResource by tasks.registering {
    description = "Copies the Google OAuth client ids from secrets.properties into the plugin jar."
    val secrets = secretsFile.asFile
    val outDir = generatedOAuthDir
    inputs.files(rootProject.fileTree(rootDir) { include("secrets.properties") })
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().file("cloudsavesync/google-oauth.properties").asFile
        out.parentFile.mkdirs()
        if (!secrets.isFile) {
            out.delete()
            logger.warn("secrets.properties not found: this build can't sign in to Google. "
                    + "Copy secrets.properties.example to secrets.properties and fill it in.")
            return@doLast
        }
        val p = Properties().apply { secrets.inputStream().use { load(it) } }
        fun value(key: String) = p.getProperty("google.$key")?.trim().orEmpty()
        // The TV (code sign-in) client is required; the Desktop (browser sign-in) client is optional.
        val required = listOf("tv.client_id", "tv.client_secret")
        val missing = required.filter { value(it).isEmpty() }
        if (missing.isNotEmpty()) {
            throw GradleException("secrets.properties is missing: " + missing.joinToString { "google.$it" })
        }
        val desktop = listOf("desktop.client_id", "desktop.client_secret")
        val keys = if (desktop.all { value(it).isNotEmpty() }) required + desktop else {
            logger.warn("No Google Desktop client in secrets.properties: /cloudsync login will use code sign-in.")
            required
        }
        out.writeText(keys.joinToString("\n", postfix = "\n") { "$it=" + value(it) })
    }
}

sourceSets.main {
    resources.srcDir(generateOAuthResource.map { generatedOAuthDir })
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("manifest.json") { expand(props) }
}

val modName = property("mod_name").toString()

tasks.named<Jar>("jar") {
    archiveBaseName.set(modName)
    // One self-contained jar: Hytale loads the plugin from it and the helper runs with it as classpath.
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from({ runtime.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ---- deploy ----

val hytaleModsDir = providers.gradleProperty("hytaleModsDir")

tasks.register<Copy>("deployPlugin") {
    group = "hytale"
    description = "Copies the built plugin jar into the directory given by the hytaleModsDir property."
    dependsOn(tasks.named("jar"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    // Placeholder only so configuration succeeds; doFirst fails the task before anything is copied.
    into(hytaleModsDir.orElse(layout.buildDirectory.dir("hytaleModsDir-not-set").map { it.asFile.path }))

    val modsDir = hytaleModsDir
    val jarName = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    val baseName = modName
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
        // Remove older versions so Hytale doesn't load two copies of the plugin.
        dir.listFiles { f -> f.name.startsWith("$baseName-") && f.name.endsWith(".jar") && f.name != jarName.get() }
            ?.forEach { old ->
                if (old.delete()) logger.lifecycle("Removed old ${old.name}")
            }
    }
    doLast {
        logger.lifecycle("Deployed ${jarName.get()} to ${modsDir.get()}")
    }
}
