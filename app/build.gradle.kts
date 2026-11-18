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
        versionCode = 19 // +1 for every release, never down: a phone refuses an "update" with a lower one (decision A25)
        versionName = "1.8" // the iPhone app's version this one matches
        ndk { abiFilters += "arm64-v8a" } // the only engine build-engine.sh makes (every phone of the last years)
    }
    buildFeatures { compose = true }
    // The release key stays outside the repo: its four details are Gradle properties in the developer's own
    // ~/.gradle/gradle.properties (FERRY_RELEASE_STORE_FILE, _STORE_PASSWORD, _KEY_ALIAS, _KEY_PASSWORD). Without them
    // the release build is simply unsigned (and won't install); debug builds don't use this and are unaffected.
    val releaseKey = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        .associateWith { providers.gradleProperty("FERRY_RELEASE_$it").orNull }
    signingConfigs {
        if (releaseKey.values.all { it != null }) create("release") {
            storeFile = file(releaseKey.getValue("STORE_FILE")!!)
            storePassword = releaseKey.getValue("STORE_PASSWORD")
            keyAlias = releaseKey.getValue("KEY_ALIAS")
            keyPassword = releaseKey.getValue("KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            // No R8 and no resource shrinking: the engine's JNI bridge names fi.ferry.core.IGCore (CLAUDE.md "The engine"),
            // and renaming or removing it would break every call into the engine.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
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
