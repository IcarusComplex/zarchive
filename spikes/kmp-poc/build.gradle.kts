plugins {
    kotlin("multiplatform") version "2.1.20"
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("app.cash.sqldelight") version "2.0.2"
}

group = "co.za.zarchive.poc"
version = "0.1"

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvm("desktop")
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
            }
        }
        // 0A spike: intermediate JVM-family source set shared by desktop(jvm) + android targets,
        // for dependencies (Jsoup) that are plain JVM jars, not published as KMP artifacts, and so
        // cannot be depended on from commonMain directly.
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("org.jsoup:jsoup:1.17.2")
                implementation("io.ktor:ktor-client-core:3.0.1")
                implementation("io.ktor:ktor-client-okhttp:3.0.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        val androidMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("app.cash.sqldelight:android-driver:2.0.2")
                implementation("androidx.webkit:webkit:1.12.1")
            }
        }
    }
}

android {
    namespace = "co.za.zarchive.poc"
    compileSdk = 36

    defaultConfig {
        applicationId = "co.za.zarchive.poc"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Spike only: debug-signed release build purely to exercise R8 minification/shrinking
            // against Jsoup. Never do this for a real release build.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

compose.desktop {
    application {
        mainClass = "co.za.zarchive.poc.MainKt"
    }
}

sqldelight {
    databases {
        create("PocDatabase") {
            packageName.set("co.za.zarchive.poc.db")
            srcDirs.setFrom("src/androidMain/sqldelight")
        }
    }
}
