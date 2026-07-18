import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

/**
 * Backend config (Worker URL + app key) is read from local.properties or the
 * environment and baked into BuildConfig. local.properties is gitignored, so no
 * endpoint/key is ever committed — and this is the app KEY, never a Hasura credential.
 */
val ingestProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun ingestProp(key: String, default: String): String =
    ingestProps.getProperty(key) ?: System.getenv(key) ?: default

// Version is driven by the GitHub Actions run number so it lines up 1:1 with the
// release tag (v1.0.<run>) — the in-app OTA updater compares this to the latest
// GitHub release. Local builds (no env) fall back to 1.
val ghRunNumber = System.getenv("GH_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.mydream.calllogger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mydream.calllogger"
        minSdk = 24
        targetSdk = 34
        versionCode = ghRunNumber
        versionName = "1.0.$ghRunNumber"
        vectorDrawables { useSupportLibrary = true }

        // Cloudflare Worker ingest endpoint + shared app key (from local.properties / env).
        buildConfigField("String", "INGEST_BASE_URL", "\"${ingestProp("INGEST_BASE_URL", "")}\"")
        buildConfigField("String", "INGEST_APP_KEY", "\"${ingestProp("INGEST_APP_KEY", "")}\"")
    }

    signingConfigs {
        // A fixed keystore committed to the repo (whitelisted in .gitignore via
        // !debug.keystore) so EVERY build — every developer machine and every CI run —
        // is signed with the SAME key. Android only lets an app update in place when the
        // signing certificate is unchanged, so the throwaway debug keystore that Gradle
        // auto-generates per machine/CI-run breaks the in-app OTA updates: each release
        // would have a different signature and refuse to install over the last. This is a
        // dedicated signing key for this internal, sideloaded app (not a Play Store key),
        // so committing it is an accepted trade-off for reproducible, update-safe signing.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "calllogger"
            keyAlias = "call-logger"
            keyPassword = "calllogger"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Reliable, constraint-aware background upload of captured calls to the Worker.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Keystore-backed storage for the per-device ingest token.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
