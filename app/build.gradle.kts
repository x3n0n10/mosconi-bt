plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

/**
 * Total commit count on the current branch, used as versionCode below. This app is
 * distributed as CI-built debug APKs rather than through a store, so a hand-maintained
 * version number would inevitably get forgotten - deriving it from git history instead
 * guarantees every build is a strictly higher versionCode than the last (required for
 * Android to treat installing a newer APK as an update rather than a conflict), with no
 * manual bookkeeping. CI's checkout step needs `fetch-depth: 0` for this to see the
 * real count rather than a shallow clone's `1`; falls back to 1 if git isn't available
 * at all (e.g. a source archive with no `.git` directory).
 */
fun gitCommitCount(): Int = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    process.waitFor()
    process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
} catch (e: Exception) {
    1
}

val appVersionCode = gitCommitCount()

android {
    namespace = "dev.x3n0n10.mosconibt"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.x3n0n10.mosconibt"
        minSdk = 33
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "0.1.0+$appVersionCode"
    }

    signingConfigs {
        getByName("debug") {
            // Fixed, checked-in debug keystore (app/debug.keystore) instead of AGP's
            // implicit per-machine default: CI runs on fresh, ephemeral VMs with no
            // pre-existing ~/.android/debug.keystore, so without this every CI build
            // would get a different random signing certificate - and Android refuses to
            // install an APK over an existing install signed with a different
            // certificate, forcing an uninstall before every single update. Carries none
            // of the risk a real release keystore would; never used for anything trusted.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    composeOptions {
        // Must match the Kotlin plugin version above (1.9.24) - the Compose compiler
        // extension is tightly coupled to the Kotlin compiler it plugs into; see
        // https://developer.android.com/jetpack/androidx/releases/compose-kotlin for the
        // compatibility table before bumping either version independently.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

configurations.all {
    resolutionStrategy {
        // Without this, the Guava version Paparazzi's dependencies pull in transitively
        // conflicts with another copy on the classpath and throws IllegalAccessError/
        // NoClassDefFoundError at test-run time. Forcing a single resolved version fixes
        // it; re-check this pin if Paparazzi is ever upgraded.
        force("com.google.guava:guava:31.1-jre")
    }
}

dependencies {
    implementation(project(":protocol"))

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
