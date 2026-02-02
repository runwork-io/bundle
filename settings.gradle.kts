rootProject.name = "bundle"

include("bundle-common")
include("bundle-resources")
include("bundle-updater")
include("bundle-bootstrap")
include("bundle-creator")
include("bundle-creator-gradle-task")
include("samples")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
