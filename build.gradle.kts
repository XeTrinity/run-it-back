plugins {
    id("net.fabricmc.fabric-loom")
    `maven-publish`
}

val mcVersion = providers.gradleProperty("mc").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricApiVersion = providers.gradleProperty("fabric_api_$mcVersion").orNull
    ?: error("No fabric_api_$mcVersion in gradle.properties; supported versions are listed there")
val minecraftRange = providers.gradleProperty("minecraft_range").get()
val modVersion = providers.gradleProperty("mod_version").get()

version = modVersion
group = providers.gradleProperty("maven_group").get()

base {
    archivesName = providers.gradleProperty("archives_base_name").get()
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    testImplementation("net.fabricmc:fabric-loader-junit:$loaderVersion")
}

// Dev-only test harness (/hcrtest). Loaded by runServer, never packaged into the release jar.
val testmod = sourceSets.create("testmod") {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}

loom {
    mods {
        create("runitback") {
            sourceSet(sourceSets.main.get())
        }
        create("runitback-testmod") {
            sourceSet(testmod)
        }
    }

    runs.named("server") {
        // Keep each target version's dev server separate so worlds don't get mixed.
        runDir = "run/$mcVersion"
        source(testmod)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "loader_version" to loaderVersion,
        "minecraft_range" to minecraftRange,
    )
    inputs.properties(props)

    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

tasks.jar {
    inputs.property("archivesName", base.archivesName)

    from("LICENSE") {
        rename { "${it}_${inputs.properties["archivesName"]}" }
    }
}

// Lets `./gradlew runServer` read console commands from stdin (used for scripted testing).
tasks.named<JavaExec>("runServer") {
    standardInput = System.`in`
}
