import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Depend on all bundle modules to verify samples compile
    implementation(project(":bundle-common"))
    implementation(project(":bundle-bootstrap"))
    implementation(project(":bundle-updater"))
    implementation(project(":bundle-creator"))

    // Coroutines for suspend functions
    implementation(libs.kotlinx.coroutines.core)
}

// This module is just for compilation verification - no tests or publishing
