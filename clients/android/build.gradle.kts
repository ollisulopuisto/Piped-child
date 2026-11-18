// Root build file for the Piped-child Android clients.
// core-model / core-network are pure Kotlin/JVM so their tests run anywhere.
// core-player is an Android library (Media3) whose manifest-parser tests run
// as JVM unit tests under Robolectric: no emulator or device required.
// app-tv / app-tablet land in M2/M3 and require the Android SDK to assemble.

plugins {
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.serialization") version "1.9.25" apply false
    kotlin("android") version "2.0.20" apply false
    id("com.android.library") version "8.6.1" apply false
}
