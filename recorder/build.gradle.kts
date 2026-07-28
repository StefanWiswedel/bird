plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Since Kotlin 2.0 the Compose compiler ships with Kotlin itself, so this
    // version tracks the Kotlin version -- removing the old compose-compiler /
    // Kotlin version-matching breakage that bites first-time Android builds.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
