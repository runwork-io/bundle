# Bundle

[![Maven Central](https://img.shields.io/maven-central/v/io.runwork/bundle?color=blue)](https://central.sonatype.com/artifact/io.runwork/bundle)

A Kotlin library for managing versioned, signed software bundles with content-addressable storage.

## Features

- **Content-Addressable Storage**: Files stored by SHA-256 hash for deduplication
- **Incremental Updates**: Smart strategy choosing between full bundle or individual file downloads
- **Ed25519 Signatures**: Manifest integrity verified with Ed25519 cryptography
- **Isolated Classloaders**: Bundles load into custom classloaders for isolation
- **Cross-Platform**: Supports macOS, Linux, and Windows
- **Type-Safe Platform API**: Platform detection via `Os` and `Architecture` enums

## Supported Platforms

Bundle uses type-safe enums for platform identification:

```kotlin
// OS options
Os.MACOS    // macOS / Darwin
Os.WINDOWS  // Windows
Os.LINUX    // Linux

// Architecture options
Architecture.ARM64   // Apple Silicon, ARM64
Architecture.X86_64  // Intel/AMD 64-bit

// Combine them or use auto-detection
val platform = Platform(Os.MACOS, Architecture.ARM64)
val currentPlatform = Platform.current  // Detects from system properties

// Convert to/from string format (for manifests)
val platformStr = platform.toString()          // "macos-arm64"
val parsed = Platform.fromString("linux-arm64") // Platform(Os.LINUX, Architecture.ARM64)
```

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.runwork:bundle:<version>")
}
```

## Usage

### Basic Bundle Management

```kotlin
// Using appId for platform-specific default storage path
val bootstrapConfig = BundleBootstrapConfig(
    appId = "com.example.myapp",
    baseUrl = "https://cdn.example.com/bundles",
    publicKey = "your-ed25519-public-key",
    shellVersion = 1,
    mainClass = "com.example.Main",
)

// Or specify an explicit storage path
val bootstrapConfig = BundleBootstrapConfig(
    appDataDir = Path("/custom/storage/path"),
    baseUrl = "https://cdn.example.com/bundles",
    publicKey = "your-ed25519-public-key",
    shellVersion = 1,
    mainClass = "com.example.Main",
)

val bootstrap = BundleBootstrap(bootstrapConfig)

// Validate and launch the bundle
when (val result = bootstrap.validate()) {
    is BundleValidationResult.Valid -> {
        val loadedBundle = bootstrap.launch(result)
        println("Bundle launched: ${loadedBundle.manifest.buildNumber}")
    }
    is BundleValidationResult.NoBundleExists -> {
        // Download initial bundle using BundleUpdater
        val updaterConfig = BundleUpdaterConfig(
            appId = "com.example.myapp",
            baseUrl = "https://cdn.example.com/bundles",
            publicKey = bootstrapConfig.publicKey,
            currentBuildNumber = 0
        )
        val updater = BundleUpdater(updaterConfig)

        when (val downloadResult = updater.downloadLatest { progress ->
            println("Downloaded ${progress.bytesDownloaded} / ${progress.totalBytes}")
        }) {
            is DownloadResult.Success -> println("Downloaded build ${downloadResult.buildNumber}")
            is DownloadResult.Failure -> println("Download failed: ${downloadResult.error}")
        }
        updater.close()
    }
}
```

### CLI Tool

Create signed bundles using the CLI:

```bash
./gradlew run --args="create \
    --input /path/to/app \
    --output /path/to/bundle.zip \
    --private-key /path/to/private.key \
    --platform macos-arm64 \
    --main-class com.example.MainKt"
```
