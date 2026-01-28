plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    `maven-publish`
    signing
}

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
}

signing {
    val signingKeyId = providers.environmentVariable("RUNWORK_SIGNING_KEY_ID").orNull
    val signingKey = providers.environmentVariable("RUNWORK_SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("RUNWORK_SIGNING_PASSWORD").orNull

    if (signingKeyId != null && signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}

tasks.withType<Sign>().configureEach {
    onlyIf { !version.toString().endsWith("SNAPSHOT") }
}
