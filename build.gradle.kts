import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

group = "co.za.mtg"
version = "1.0.21"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvm("desktop")
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // Intermediate JVM-family source set shared by desktop(jvm) + android targets, for
        // dependencies (Jsoup) that are plain JVM jars, not published as KMP artifacts, and so
        // cannot be depended on from commonMain directly. Also hosts the networking/parsing/cache
        // stack itself (Searchers/CardImageService/ScryfallSetIndex/GitHubService/SearchEngine),
        // per the pattern validated in the Phase 0 spike (spikes/kmp-poc).
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.jsoup)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.playwright)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.exposed.core)
                implementation(libs.exposed.jdbc)
                implementation(libs.h2)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.androidx.webkit)
                implementation(libs.androidx.splashscreen)
            }
        }
    }
}

android {
    namespace = "co.za.zarchive"
    compileSdk = 36

    defaultConfig {
        applicationId = "co.za.zarchive"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = version.toString()
        manifestPlaceholders["appLabel"] = "ZArchive"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Distinct applicationId (and therefore a separate data directory/database) from the release
    // build, so a debug build and a signed release build can be installed side by side on the same
    // device for comparison testing -- without this they share "co.za.zarchive" and Android treats
    // them as the same app, refusing to install one over the other once they're signed differently
    // (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "ZArchive Debug"
        }
    }

    // Release signing is driven entirely by environment variables (set from GitHub Actions
    // secrets in CI -- see .github/workflows/release.yml) so the keystore and its password never
    // touch the repo. A local `assembleRelease` without these env vars set falls back to no
    // explicit signing config (an unsigned/debug-signed build) -- fine for local testing, but
    // every real release must go through CI where the secrets are present.
    val ksBase64 = System.getenv("ANDROID_KEYSTORE_BASE64")
    if (!ksBase64.isNullOrBlank()) {
        val decodedKeystore = layout.buildDirectory.file("zarchive-release.jks").get().asFile
        decodedKeystore.parentFile.mkdirs()
        decodedKeystore.writeBytes(Base64.getDecoder().decode(ksBase64))
        signingConfigs {
            create("release") {
                storeFile = decodedKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Android-only local database (Phase 5) — desktop's Exposed/H2 database (data/AppDatabase.kt)
// stays completely separate and untouched, per the plan's explicit decision.
sqldelight {
    databases {
        create("ZArchiveDatabase") {
            packageName.set("data.db")
            srcDirs.setFrom("src/androidMain/sqldelight")
        }
    }
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

// Generates BuildInfo.kt with the app version baked in at compile time. Lives in commonMain's
// generated sources so both the desktop and Android apps report the same BuildInfo.VERSION.
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

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generatedKotlinDir)

// Matches every Kotlin compile task across every target/variant (compileKotlinDesktop,
// compileDebugKotlinAndroid, compileReleaseKotlinAndroid, etc.) by task type rather than name —
// a name-prefix match ("compileKotlin*") silently misses Android's "compile<Variant>Kotlin<Target>"
// naming and only surfaces as a Gradle task-validation error under certain build orderings.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateBuildInfo)
}

// Inject debug flag only during `gradlew run` — never present in packaged distributions
afterEvaluate {
    (tasks.findByName("run") as? JavaExec)?.systemProperty("mtg.debug", "true")
}
