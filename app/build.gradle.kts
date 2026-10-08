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
            isMinifyEnabled = false
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
}
