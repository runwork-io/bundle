rootProject.name = "bundle"

include("bundle-common")
include("bundle-updater")
include("bundle-bootstrap")
include("bundle-creator")
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
