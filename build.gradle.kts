plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
    // Pinned below latest deliberately: 1.3.5 requires the standalone Kotlin Compose
    // compiler Gradle plugin (K2-era Compose compiler split from the Kotlin plugin),
    // which conflicts with this project's plugin setup and fails the build with a
    // "Compose compiler plugin required" error. 1.3.2 predates that requirement.
    // Bump only alongside migrating to the standalone Compose compiler plugin.
    id("app.cash.paparazzi") version "1.3.2" apply false
}
