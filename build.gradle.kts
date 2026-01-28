plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
    `maven-publish`
    signing
}

group = property("GROUP").toString()
version = property("VERSION_NAME").toString()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("io.runwork.bundle.cli.BundleCreatorCliKt")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okio:okio:3.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set(property("POM_NAME").toString())
                description.set(property("POM_DESCRIPTION").toString())
                url.set(property("POM_URL").toString())

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
    }

    repositories {
        maven {
            name = "sonatype"
            val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl

            credentials {
                username = findProperty("ossrhUsername")?.toString() ?: System.getenv("OSSRH_USERNAME")
                password = findProperty("ossrhPassword")?.toString() ?: System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}

signing {
    val signingKeyId = findProperty("signing.keyId")?.toString() ?: System.getenv("SIGNING_KEY_ID")
    val signingKey = findProperty("signing.key")?.toString() ?: System.getenv("SIGNING_KEY")
    val signingPassword = findProperty("signing.password")?.toString() ?: System.getenv("SIGNING_PASSWORD")

    if (signingKeyId != null && signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}

tasks.withType<Sign>().configureEach {
    onlyIf { !version.toString().endsWith("SNAPSHOT") }
}
