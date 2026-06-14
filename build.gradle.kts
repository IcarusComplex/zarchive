import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.compose") version "1.6.11"
    kotlin("plugin.serialization") version "1.9.23"
}

group = "co.za.mtg"
version = "0.0.2"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("io.ktor:ktor-client-core:2.3.10")
    implementation("io.ktor:ktor-client-cio:2.3.10")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.microsoft.playwright:playwright:1.52.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            // Msi is listed so `packageMsi` works if WiX is installed; the primary
            // deliverable is the portable app image from `createDistributable` (no WiX needed).
            targetFormats(TargetFormat.Msi)
            packageName = "ZArchive"
            packageVersion = "0.0.2"
            description = "Search South African MTG stores for card singles"
            vendor = "ZArchive"
            copyright = "© 2026 ZArchive"

            // Trim the bundled JRE to just the modules the app actually uses.
            includeAllModules = true

            windows {
                iconFile.set(project.file("build-tools/ZArchive.ico"))
                // Used by the MSI installer (Start Menu group + shortcuts); harmless for app image.
                menuGroup = "ZArchive"
                menu = true
                shortcut = true
                upgradeUuid = "bb68f68c-b99b-48ae-a23b-bee6ac0e8f82"
            }
        }
    }
}

// Generate BuildInfo.kt — injects app version + GitHub crash-report token from secrets.properties.
// Generates BuildInfo.kt with the app version baked in at compile time.
val generatedKotlinDir = layout.buildDirectory.dir("generated/kotlin")

val generateBuildInfo by tasks.registering {
    outputs.dir(generatedKotlinDir)
    inputs.property("version", version)
    doLast {
        val out = generatedKotlinDir.get().file("data/BuildInfo.kt").asFile
        out.parentFile.mkdirs()
        out.writeText("package data\n\nobject BuildInfo {\n    const val VERSION = \"$version\"\n}\n")
    }
}

tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }

sourceSets {
    main {
        kotlin.srcDir(generatedKotlinDir)
    }
}

// Inject debug flag only during `gradlew run` — never present in packaged distributions
afterEvaluate {
    (tasks.findByName("run") as? JavaExec)?.systemProperty("mtg.debug", "true")
}
