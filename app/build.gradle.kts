plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

android {
    namespace = "dev.x3n0n10.mosconibt"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.x3n0n10.mosconibt"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
