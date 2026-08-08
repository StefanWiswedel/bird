// Imported rather than fully qualified: inside the Kotlin DSL a bare `java.` resolves to
// the JavaPluginExtension, not the package, so `java.time.Instant` fails to compile.
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** A Gradle property if set, else an environment variable, else null. Keystore passwords
 *  come from one or the other so nothing secret is ever committed or passed on a command
 *  line; CI uses the env vars, a local release build uses ~/.gradle/gradle.properties. */
fun secret(property: String, env: String): String? =
    providers.gradleProperty(property).orNull ?: System.getenv(env)

// SIGNING — the part of this file that can destroy data.
//
// Android refuses to install an APK over one signed with a different key; the only way
// through is an uninstall, and uninstalling this app deletes station.db AND the ~67 MB of
// BirdNET models in getExternalFilesDir("models"), which are gitignored and restorable
// only over adb from a computer — precisely what the release flow exists to avoid. So the
// key must never change, and the FALLBACK matters as much as the config: with no keystore
// supplied we sign with the debug key, which is the key the phone already has, rather than
// emitting an unsigned APK that Android will not install at all.
val stationKeystore = (providers.gradleProperty("stationKeystore").orNull
    ?: System.getenv("STATION_KEYSTORE"))
    ?.takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.isFile }

// WHICH KEY SIGNED THIS BUILD, written where the release workflow can read it. Gradle
// makes the fallback decision above; a workflow that re-derived the answer for itself
// (from whether a secret happens to be set) would be a second copy of that decision, free
// to disagree with it. Whether the next install goes over the top or forces an uninstall
// depends entirely on this, so it must be visible without inspecting the APK.
val signingKeyLabel = if (stationKeystore != null) "releasekey" else "debugkey"
layout.buildDirectory.get().asFile.let {
    it.mkdirs(); it.resolve("signing-key.txt").writeText(signingKeyLabel)
}

// The always-on balcony station. A SEPARATE application from :app (Biomon the field
// recorder), deliberately: they have opposite lifecycles. Biomon is picked up, pointed at
// something and put away; the station is bolted to a wall and must never stop. Sharing one
// APK would mean one process whose notification, wake locks and service type had to serve
// both, and the station's single hard requirement is that it does not die.
//
// Reused code is FORKED rather than shared through a library module. Extracting a common
// module would mean editing :app, and :app currently works; the project rule is not to
// regress working behaviour in pursuit of a refactor. Each forked file names its origin.
android {
    namespace = "dk.biomon.station"
    compileSdk = 36

    defaultConfig {
        applicationId = "dk.biomon.station"
        // API 29 is the OnePlus 5T on OxygenOS 10. It is also the floor for what we need:
        // background microphone access on Android 10 REQUIRES a foreground service with
        // foregroundServiceType="microphone", which arrived in API 29.
        minSdk = 29
        targetSdk = 36
        // Sideloading an update over the top requires a versionCode strictly greater than
        // the installed one; a hardcoded 1 means every release after the first has to be
        // uninstalled first, and uninstalling this app deletes station.db and the two
        // BirdNET models. CI passes the run number. A local build has no env var and stays
        // at 1, which is fine — a local build is installed over adb anyway.
        versionCode = System.getenv("STATION_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "0.1.0"

        // BUILD STAMP. versionName alone cannot answer the only question that actually
        // gets asked — "is the phone running what I just built?" — because it stays
        // 0.1.0 across every rebuild. This cost real confusion once: four backend builds
        // were installed in a session while the dashboard still served an older UI, and
        // there was no way to tell by looking. The commit and the build time are what
        // distinguish two builds of the same version, so both are stamped in and surfaced
        // in /api/health and in the dashboard header.
        //
        // Resolved at CONFIGURATION time and failing soft: a build must not break because
        // git is absent or this is a source export with no repository.
        val gitSha = providers.exec {
            commandLine("git", "rev-parse", "--short=8", "HEAD")
        }.standardOutput.asText.map { it.trim() }.orElse("nogit").get()
        val gitDirty = providers.exec {
            commandLine("git", "status", "--porcelain")
        }.standardOutput.asText.map { if (it.isBlank()) "" else "+" }.orElse("").get()
        val builtAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.now())

        buildConfigField("String", "GIT_SHA", "\"$gitSha$gitDirty\"")
        buildConfigField("String", "BUILT_AT", "\"$builtAt\"")

        // WHERE THE DASHBOARD UPDATES ITSELF FROM (POST /api/dashboard/update).
        //
        // COMPILE-TIME CONSTANT, NEVER CLIENT-SUPPLIED. This is the entire security model
        // for that endpoint, which is otherwise unauthenticated like the rest of the LAN
        // API: because the station can only ever fetch this one URL, the worst any device
        // on the network can do is make it re-download our own dashboard from our own
        // repo. Accepting a URL in the request — even "just for testing" — turns a harmless
        // endpoint into arbitrary remote code execution in the browser of anyone who opens
        // the dashboard afterwards. Point it at a fork by rebuilding, not by parameter.
        buildConfigField("String", "DASHBOARD_SOURCE_URL",
            "\"https://raw.githubusercontent.com/StefanWiswedel/bird/main/" +
            "recorder/station/src/main/assets/www/\"")
    }

    // See the signing block above the android {} block for why the fallback exists.
    signingConfigs {
        if (stationKeystore != null) {
            create("release") {
                storeFile = stationKeystore
                storePassword = secret("stationKeystorePassword", "STATION_KEYSTORE_PASSWORD")
                // Defaulted because the keystore we expect to be handed IS the original
                // debug keystore (README, "Releases"), whose alias is always this. A wrong
                // alias fails the build loudly rather than producing a mis-signed APK.
                keyAlias = secret("stationKeyAlias", "STATION_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = secret("stationKeyPassword", "STATION_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (stationKeystore != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The BirdNET .tflite is pushed to the device, not bundled (see BirdNet.kt), so there
    // is no large asset to keep uncompressed. The species table and dashboard are text and
    // compress well.
    androidResources { noCompress += listOf("tflite") }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// NO NEW DEPENDENCIES. Everything here is already in the Gradle cache from :app, which is
// what lets this project build --offline. The HTTP server is written against ServerSocket
// rather than pulling in NanoHTTPD for exactly that reason: a dependency that will not
// resolve offline is a build that fails on the day the wifi is down.
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
