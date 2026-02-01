plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish) apply false
}

subprojects {
    tasks.withType<Test> {
        // Set a 20-second timeout for all tests
        systemProperty("junit.jupiter.execution.timeout.default", "20s")
    }
}
