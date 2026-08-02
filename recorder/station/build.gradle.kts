plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
