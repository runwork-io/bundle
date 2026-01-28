plugins {
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.serialization") version "2.3.0" apply false
}

allprojects {
    group = property("GROUP").toString()
    version = property("VERSION_NAME").toString()
}

subprojects {
    pluginManager.withPlugin("maven-publish") {
        configure<PublishingExtension> {
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
    }
}
