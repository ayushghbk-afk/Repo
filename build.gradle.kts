// Top-level build file
plugins {
    id("com.android.application") version "9.3.2" apply false
    // Compose compiler Gradle plugin. Version must match the Kotlin compiler
    // used by AGP 9's built-in Kotlin support (kotlin-gradle-plugin 2.2.10 is
    // what AGP 9.3.2 embeds). See app/build.gradle.kts.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
