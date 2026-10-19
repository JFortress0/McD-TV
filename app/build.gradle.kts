plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// GitHub Actions sets GITHUB_RUN_NUMBER. Using it as the version code means every
// cloud build is "newer" than the last, so the Fire Stick installs it as an update.
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// The signing key is NOT stored in git. CI rebuilds it from the KEYSTORE_BASE64 secret.
// Same key every build = updates install over the old app without uninstalling.
val keystoreFile = rootProject.file("keystore/mcdtv.jks")

/** Spotify Client ID for local builds (see defaultConfig). Only 32 hex characters pass, so nothing else gets baked in. */
fun spotifyClientId(): String {
    val local = rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
        java.util.Properties().apply { f.inputStream().use { load(it) } }.getProperty("spotifyClientId")
    }
    val raw = (findProperty("spotifyClientId") as String?) ?: local ?: System.getenv("SPOTIFY_CLIENT_ID") ?: ""
    return raw.trim().lowercase().takeIf { Regex("[0-9a-f]{32}").matches(it) } ?: ""
}

android {
    namespace = "com.mcd.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcd.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.2.$runNumber"
        // Optional: CI bakes in the TMDB key from the TMDB_API_KEY GitHub secret,
        // so nobody has to enter it on the TV. A key saved in Phone setup still wins.
        buildConfigField("String", "TMDB_KEY", "\"${System.getenv("TMDB_API_KEY") ?: ""}\"")
        buildConfigField("String", "MDBLIST_KEY", "\"${System.getenv("MDBLIST_API_KEY") ?: ""}\"")
        // Optional Spotify Client ID for a local build: spotifyClientId=... in ~/.gradle/gradle.properties or
        // local.properties (both outside git), or the SPOTIFY_CLIENT_ID env var. Not a secret, but never committed.
        // The Client ID saved on the Control page wins. Blank = set it on the Control page.
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${spotifyClientId()}\"")
    }

    signingConfigs {
        create("mcd") {
            storeFile = keystoreFile
            storePassword = "mcdtv-sideload"
            keyAlias = "mcdtv"
            keyPassword = "mcdtv-sideload"
        }
    }

    buildTypes {
        release {
            // Release builds run much smoother than debug builds on a Fire Stick.
            // R8 shrinks and optimizes the code (smaller dex, less RAM, faster start on 1 GB sticks).
            // Keep rules: app/proguard-rules.pro. Names are not obfuscated, so stack traces stay readable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreFile.exists()) {
                signingConfigs.getByName("mcd")
            } else {
                // Fallback so the build never breaks; updates may need an uninstall first.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // JVM unit tests (app/src/test): Android framework calls return 0/null/false instead of throwing "Stub!".
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // Lint warnings should never block an APK for a personal sideloaded app.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    // Installs the baseline profile (src/main/baseline-prof.txt) on sideloaded installs, where no app store does it.
    implementation(libs.androidx.profileinstaller)
    // Spotify (phase 1): sign-in library from Maven Central, App Remote AAR from github.com/spotify/android-sdk
    // (v0.8.0, Apache 2.0, sha256 b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198).
    implementation(libs.spotify.auth)
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
