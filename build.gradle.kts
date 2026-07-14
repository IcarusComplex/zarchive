import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

group = "co.za.mtg"
version = "1.1.2"

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
                implementation(libs.play.services.auth)
                implementation(libs.androidx.work.runtime.ktx)
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
        // Must strictly increase release-to-release or Android's package installer refuses to
        // install an update over an existing install -- derived from the semantic version
        // (1.0.22 -> 10022) instead of a separately-tracked counter, so bumping `version` above
        // is the only step needed per release.
        versionCode = version.toString().substringBefore('-').split(".").let { (maj, min, patch) ->
            maj.toInt() * 10000 + min.toInt() * 100 + patch.toInt()
        }
        versionName = version.toString()
        manifestPlaceholders["appLabel"] = "ZArchive"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // BuildConfig.APPLICATION_ID / .DEBUG let shared androidMain code (the Google OAuth flow) tell
    // debug and release apart at runtime without any Android-specific plumbing in jvmCommonMain.
    buildFeatures {
        buildConfig = true
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

// Generates GoogleAuthConfig.kt from secrets.properties (git-ignored, never committed -- see
// .gitignore) so the OAuth client id/secret downloaded from Google Cloud Console never lands in
// tracked source, matching how the Android release signing config is already env-var-driven
// (build.gradle.kts's ANDROID_KEYSTORE_* handling above) rather than checked in. Missing file/keys
// fall back to REPLACE_WITH_* placeholders so a fresh clone (or CI without secrets.properties)
// still compiles -- sync is simply non-functional until the file is restored.
val secretsProps = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secretOrPlaceholder(key: String, placeholder: String) = secretsProps.getProperty(key) ?: placeholder

val generateGoogleAuthConfig by tasks.registering {
    outputs.dir(generatedKotlinDir)
    // inputs.file(...).optional(true) still fails Gradle 8.13's strict validation when the file
    // is genuinely absent (always true on CI -- secrets.properties is git-ignored by design), so
    // only register it as a tracked input when it actually exists locally.
    rootProject.file("secrets.properties").takeIf { it.exists() }?.let { inputs.file(it) }
    doLast {
        val desktopClientId = secretOrPlaceholder("google.desktop.clientId", "REPLACE_WITH_DESKTOP_CLIENT_ID.apps.googleusercontent.com")
        val desktopClientSecret = secretOrPlaceholder("google.desktop.clientSecret", "REPLACE_WITH_DESKTOP_CLIENT_SECRET")
        val webClientId = secretOrPlaceholder("google.web.clientId", "REPLACE_WITH_WEB_CLIENT_ID.apps.googleusercontent.com")
        val webClientSecret = secretOrPlaceholder("google.web.clientSecret", "REPLACE_WITH_WEB_CLIENT_SECRET")
        val out = generatedKotlinDir.get().file("network/GoogleAuthConfig.kt").asFile
        out.parentFile.mkdirs()
        out.writeText("""
            package network

            // Generated at build time from secrets.properties (git-ignored) -- see build.gradle.kts's
            // generateGoogleAuthConfig task. Do not edit directly; edit secrets.properties instead.
            //
            // Only the Desktop and Web clients are referenced anywhere (GoogleOAuthFlow.desktop.kt /
            // .android.kt) -- Android's own "Android" OAuth client type (registered in Cloud Console,
            // keyed by package name + signing SHA-1) is validated automatically by Google Play
            // Services against the calling app, with no client ID needed in code at all.
            object GoogleAuthConfig {
                const val DESKTOP_CLIENT_ID = "$desktopClientId"
                const val DESKTOP_CLIENT_SECRET = "$desktopClientSecret"

                // "Web application" client used only as the serverClientId for Android's
                // Authorization API requestOfflineAccess() -- never used for a redirect, no
                // hosting/domain involved. The app redeems the resulting serverAuthCode itself.
                const val WEB_CLIENT_ID = "$webClientId"
                const val WEB_CLIENT_SECRET = "$webClientSecret"

                // drive.file is the real access we need; userinfo.email is only so the settings UI can
                // show "Connected as you@gmail.com" -- both are Google "non-sensitive" scopes (no
                // verification review required).
                const val SCOPE = "https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/userinfo.email"
                const val SYNC_FOLDER_NAME = "ZArchive"
                const val SYNC_BLOB_FILE_NAME = "zarchive-sync.json"
            }

        """.trimIndent())
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generatedKotlinDir)

// Matches every Kotlin compile task across every target/variant (compileKotlinDesktop,
// compileDebugKotlinAndroid, compileReleaseKotlinAndroid, etc.) by task type rather than name —
// a name-prefix match ("compileKotlin*") silently misses Android's "compile<Variant>Kotlin<Target>"
// naming and only surfaces as a Gradle task-validation error under certain build orderings.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateBuildInfo)
    dependsOn(generateGoogleAuthConfig)
}

// Inject debug flag only during `gradlew run` — never present in packaged distributions
afterEvaluate {
    (tasks.findByName("run") as? JavaExec)?.systemProperty("mtg.debug", "true")
}
