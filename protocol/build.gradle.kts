plugins {
    kotlin("jvm")
}

kotlin {
    // Matches :app's Java 17 requirement (AGP 8.5.2 needs 17+) so CI only needs one JDK.
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
