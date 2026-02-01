import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.vanniktech.maven.publish")
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

application {
    mainClass.set("io.runwork.bundle.creator.cli.BundleCreatorCliKt")
}

dependencies {
    api(project(":bundle-common"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

val publishVersion: String? = providers.gradleProperty("VERSION_NAME").orNull

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = property("GROUP").toString(),
        artifactId = "bundle-creator",
        version = publishVersion ?: "0.0.0-LOCAL"
    )

    pom {
        name.set("Bundle Creator")
        description.set("Create and sign bundles for distribution")
        url.set(property("POM_URL").toString())
        inceptionYear.set("2025")

        licenses {
            license {
                name.set(property("POM_LICENCE_NAME").toString())
                url.set(property("POM_LICENCE_URL").toString())
                distribution.set(property("POM_LICENCE_DIST").toString())
            }
        }

        developers {
            developer {
                id.set(property("POM_DEVELOPER_ID").toString())
                name.set(property("POM_DEVELOPER_NAME").toString())
                url.set(property("POM_DEVELOPER_URL").toString())
            }
        }

        scm {
            url.set(property("POM_SCM_URL").toString())
            connection.set(property("POM_SCM_CONNECTION").toString())
            developerConnection.set(property("POM_SCM_DEV_CONNECTION").toString())
        }
    }
}

val validateVersionForPublish by tasks.registering {
    doLast {
        if (publishVersion == null) {
            throw GradleException(
                "VERSION_NAME must be set when publishing to Maven Central. " +
                        "Use: ./gradlew :bundle-creator:publishAndReleaseToMavenCentral -PVERSION_NAME=x.y.z"
            )
        }
    }
}

tasks.matching {
    it.name.contains("MavenCentral") ||
            it.name.startsWith("publish") ||
            it.name.startsWith("sign")
}.configureEach {
    dependsOn(validateVersionForPublish)
}
