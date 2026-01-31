rootProject.name = "bundle"

include("bundle-common")
include("bundle-updater")
include("bundle-bootstrap")
include("bundle-creator")

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
