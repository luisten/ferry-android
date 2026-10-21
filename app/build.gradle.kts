// Exact versions only; every artifact's SHA-256 is in gradle/verification-metadata.xml (CLAUDE.md "Dependencies").
plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
}

android {
    namespace = "fi.ferry.messenger"
    compileSdk = 37 // Compose 1.12 needs it; targetSdk decides how the app behaves
    ndkVersion = "30.0.16248370" // the one the engine is built with; AGP only strips with it
    defaultConfig {
        applicationId = "fi.ferry.messenger"
        minSdk = 26 // build-engine.sh's API level
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" } // the only engine build-engine.sh makes (every phone of the last years)
    }
    buildFeatures { compose = true }
    sourceSets.getByName("main").jniLibs.directories += "build/engine/jniLibs"
}

// The engine, before every build (Go's cache makes an unchanged one quick).
val engine = tasks.register<Exec>("engine") { commandLine(rootProject.file("build-engine.sh")) }
tasks.named("preBuild") { dependsOn(engine) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0") // the engine's JSON, as Swift's Codable
    // Video and voice playback, and shrinking videos over Instagram's 25 MB before sending (A6, the user's choice).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui-compose:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    testImplementation("junit:junit:4.13.2") // tests only, never in the app
}
