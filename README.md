# Bundle

A Kotlin library for managing versioned, signed software bundles with content-addressable storage.

## Features

- **Content-Addressable Storage**: Files stored by SHA-256 hash for deduplication
- **Incremental Updates**: Smart strategy choosing between full bundle or individual file downloads
- **Ed25519 Signatures**: Manifest integrity verified with Ed25519 cryptography
- **Isolated Classloaders**: Bundles load into custom classloaders for isolation
- **Cross-Platform**: Supports macOS, Linux, and Windows

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.runwork:bundle:0.1.0")
}
```

## Usage

### Basic Bundle Management

```kotlin
val config = BundleConfig(
    baseUrl = "https://cdn.example.com/bundles",
    publicKey = "your-ed25519-public-key",
    bundleDir = Path("/path/to/bundles"),
    bundleName = "my-app"
)

val manager = BundleManager(config)

// Check for updates
val status = manager.checkForUpdates()
if (status is UpdateStatus.Available) {
    // Download with progress reporting
    manager.download { progress ->
        println("Downloaded ${progress.bytesDownloaded} / ${progress.totalBytes}")
    }
}

// Load the bundle
val bundle = manager.load()
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

## License

Apache License 2.0
