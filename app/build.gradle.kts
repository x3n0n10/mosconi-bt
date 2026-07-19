import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

/**
 * Release signing is deliberately kept out of source control and CI - this project has
 * no automated release pipeline; whoever builds a signed release APK does it locally.
 * These four keys go in the same gitignored `local.properties` Android Studio already
 * uses for `sdk.dir` (see README's "Building a release APK" section for the one-time
 * `keytool` command to generate a real keystore). If they're absent, the `release`
 * build type is simply left unsigned rather than failing the build, so `assembleDebug`/
 * tests/CI are never affected by whether a release keystore happens to be configured.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localProperty(key: String): String? = localProperties.getProperty(key)?.takeIf { it.isNotBlank() }

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
        create("release") {
            localProperty("release.storeFile")?.let { storeFile = file(it) }
            storePassword = localProperty("release.storePassword")
            keyAlias = localProperty("release.keyAlias")
            keyPassword = localProperty("release.keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only sign if a real keystore is actually configured (see localProperty
            // above) - otherwise leave this build type unsigned rather than failing
            // assembleRelease outright for anyone who hasn't set one up yet.
            if (localProperty("release.storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
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
