plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    // Since Kotlin 2.0 the Compose compiler ships with Kotlin, so this version
    // tracks the Kotlin version — no separate compose-compiler matching.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
