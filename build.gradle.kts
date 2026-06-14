import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.compose") version "1.6.11"
    kotlin("plugin.serialization") version "1.9.23"
}

group = "co.za.mtg"
version = "0.0.9"

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

// jpackage requires a plain MAJOR.MINOR.PATCH version; strip any prerelease suffix (e.g. -beta.1).
val packageVer = version.toString().substringBefore('-')
// macOS jpackage enforces MAJOR >= 1 for CFBundleVersion; remap 0.x.y → 1.0.y.
val macPackageVer = packageVer.let { v ->
    val parts = v.split(".")
    if (parts[0] == "0") "1.0.${parts.getOrElse(2) { "0" }}" else v
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            // Msi is listed so `packageMsi` works if WiX is installed; the primary
            // deliverable is the portable app image from `createDistributable` (no WiX needed).
            // Msi: available via packageMsi if WiX is installed.
            // Dmg: available via packageDmg on macOS (requires MAJOR >= 1).
            // Primary deliverable is createDistributable (no format-specific tooling needed).
            targetFormats(TargetFormat.Msi)
            packageName = "ZArchive"
            packageVersion = packageVer
            description = "Search South African MTG stores for card singles"
            vendor = "ZArchive"
            copyright = "© 2026 ZArchive"

            includeAllModules = true

            windows {
                iconFile.set(project.file("build-tools/ZArchive.ico"))
                menuGroup = "ZArchive"
                menu = true
                shortcut = true
                upgradeUuid = "bb68f68c-b99b-48ae-a23b-bee6ac0e8f82"
            }

            macOS {
                // Required by jpackage; used as the bundle identifier in Info.plist.
                bundleID = "co.za.zarchive"
                packageVersion = macPackageVer
            }
        }
    }
}

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
