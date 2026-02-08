# Bundle

[![Maven Central](https://img.shields.io/maven-central/v/io.runwork/bundle-bootstrap?color=blue)](https://central.sonatype.com/artifact/io.runwork/bundle-bootstrap)

A Kotlin library for managing versioned, signed software bundles with content-addressable storage.

## Features

- **Content-Addressable Storage**: Files stored by SHA-256 hash for deduplication
- **Incremental Updates**: Smart strategy choosing between full bundle or individual file downloads
- **Ed25519 Signatures**: Manifest integrity verified with Ed25519 cryptography
- **Isolated Classloaders**: Bundles load into custom classloaders for isolation
- **Cross-Platform**: Supports macOS, Linux, and Windows
- **Type-Safe Platform API**: Platform detection via `Os` and `Arch` enums

## Architecture

Bundle uses a **shell + bundle** architecture:

- **Shell**: A thin native launcher that validates, downloads, and launches bundles. It embeds `bundle-bootstrap` and `bundle-updater`.
- **Bundle**: Your application code, loaded into an isolated classloader. It can use `bundle-updater` for background self-updates and `bundle-resources` for platform-aware resource resolution.

On startup, the shell validates the current bundle (or downloads one if none exists), then launches it. Once running, the bundle can check for updates in the background and signal the shell to restart with the new version.

## Artifacts

All artifacts are published to Maven Central under the `io.runwork` group:

| Artifact | Description | Use Case |
|----------|-------------|----------|
| `bundle-common` | Shared data classes, verification, and CAS | Dependency of all other modules |
| `bundle-bootstrap` | Validate and launch bundles | Shell application |
| `bundle-updater` | Download bundles (initial + updates) | Shell application AND inside bundle |
| `bundle-creator` | Create and sign bundles (library) | CI pipelines |
| `bundle-creator-gradle-task` | Gradle task for bundle creation | Gradle-based CI pipelines |
| `bundle-resources` | Platform-aware resource resolution | Inside bundle (optional) |

### Dependency Setup

Add artifacts to your `build.gradle.kts` based on your use case:

```kotlin
dependencies {
    // For shell applications (validates, downloads, and launches bundles)
    implementation("io.runwork:bundle-bootstrap:<version>")

    // For applications inside bundles (self-update + resource resolution)
    implementation("io.runwork:bundle-updater:<version>")
    implementation("io.runwork:bundle-resources:<version>")

    // For CI/build scripts (creating bundles programmatically)
    implementation("io.runwork:bundle-creator:<version>")
}
```

For Gradle-based bundle creation, add to your buildscript:

```kotlin
buildscript {
    dependencies {
        classpath("io.runwork:bundle-creator-gradle-task:<version>")
    }
}
```

## Supported Platforms

Bundle uses type-safe enums for platform identification:

```kotlin
// OS options
Os.MACOS    // macOS / Darwin
Os.WINDOWS  // Windows
Os.LINUX    // Linux

// Architecture options
Arch.ARM64  // Apple Silicon, ARM64
Arch.X64    // Intel/AMD 64-bit

// Combine them or use auto-detection
val platform = Platform(Os.MACOS, Arch.ARM64)
val currentPlatform = Platform.current  // Detects from system properties

// Convert to/from string format (for manifests)
val platformStr = platform.toString()          // "macos-arm64"
val parsed = Platform.fromString("linux-arm64") // Platform(Os.LINUX, Arch.ARM64)
```

## Usage

### Shell Application: Validate and Launch Bundles

```kotlin
val config = BundleBootstrapConfig(
    appDataDir = Path.of(System.getProperty("user.home"), ".myapp"),
    bundleSubdirectory = "bundle",
    baseUrl = "https://updates.myapp.com",
    publicKey = "MCowBQYDK2VwAyEA...", // Ed25519 public key (Base64)
    shellVersion = 1,
    platform = Platform.current,
    mainClass = "com.myapp.Main",
)

val bootstrap = BundleBootstrap(config)

// validateAndLaunch handles the full lifecycle: validate → download → launch.
// On successful launch, the flow blocks until the bundle's main() completes
// and then calls exitProcess — the flow never completes in that case.
bootstrap.validateAndLaunch().collect { event ->
    when (event) {
        BundleStartEvent.Progress.ValidatingManifest -> println("Validating manifest...")
        is BundleStartEvent.Progress.ValidatingFiles -> {
            println("Validating files: ${event.percentCompleteInt}%")
        }
        is BundleStartEvent.Progress.Downloading -> {
            println("Downloading: ${event.progress.percentCompleteInt}%")
        }
        BundleStartEvent.Progress.Launching -> println("Launching...")
        is BundleStartEvent.Failed -> {
            println("Failed: ${event.reason} (retryable=${event.isRetryable})")
        }
        is BundleStartEvent.ShellUpdateRequired -> {
            println("Shell update required: ${event.currentVersion} -> ${event.requiredVersion}")
            event.updateUrl?.let { println("Download from: $it") }
        }
    }
}
```

### Bundle Self-Update (Inside Running Bundle)

Once your bundle is running, it can check for updates in the background:

```kotlin
val launchConfig = BundleJson.decodingJson.decodeFromString<BundleLaunchConfig>(args[0])

val config = BundleUpdaterConfig(
    appDataDir = Path.of(launchConfig.appDataDir),
    bundleSubdirectory = launchConfig.bundleSubdirectory,
    baseUrl = launchConfig.baseUrl,
    publicKey = launchConfig.publicKey,
    currentBuildNumber = launchConfig.currentBuildNumber,
    platform = Platform.fromString(launchConfig.platform),
    checkInterval = 6.hours,
)

val updater = BundleUpdater(config)

// Collect update events from the Flow
updater.runInBackground().collect { event ->
    when (event) {
        BundleUpdateEvent.Checking -> println("Checking for updates...")
        BundleUpdateEvent.UpToDate -> println("Already running latest version")
        is BundleUpdateEvent.UpdateAvailable -> {
            println("Update available: ${event.info.currentBuildNumber} -> ${event.info.newBuildNumber}")
        }
        is BundleUpdateEvent.Downloading -> {
            println("Downloading: ${event.progress.percentCompleteInt}%")
        }
        is BundleUpdateEvent.UpdateReady -> {
            println("Update ready! Build ${event.newBuildNumber}")
            // Prompt user to restart, or auto-restart
        }
        is BundleUpdateEvent.Error -> println("Update error: ${event.error.message}")
        is BundleUpdateEvent.CleanupComplete -> {
            println("Cleaned up ${event.result.versionsRemoved.size} old versions")
        }
    }
}
```

### Platform-Specific Resources (Inside Bundle)

`BundleResources` resolves files from the `resources/` folder with platform priority:

```kotlin
val launchConfig = BundleJson.decodingJson.decodeFromString<BundleLaunchConfig>(args[0])
BundleResources.init(launchConfig)

// Resolve with platform fallback:
//   1. resources/{os}-{arch}/path  (e.g., resources/macos-arm64/config.json)
//   2. resources/{os}/path         (e.g., resources/macos/config.json)
//   3. resources/common/path       (e.g., resources/common/config.json)
val configPath = BundleResources.resolveOrThrow("config/settings.json")

// Load native libraries (auto-resolves platform naming: .dylib / .dll / .so)
BundleResources.loadNativeLibrary("whisper")
```

### Creating Bundles with Gradle Task

The recommended way to create bundles in Gradle-based projects:

```kotlin
// build.gradle.kts
import io.runwork.bundle.gradle.BundleCreatorTask

buildscript {
    dependencies {
        classpath("io.runwork:bundle-creator-gradle-task:<version>")
    }
}

tasks.register<BundleCreatorTask>("createBundle") {
    inputDirectory.set(layout.buildDirectory.dir("install/myapp"))
    outputDirectory.set(layout.buildDirectory.dir("bundle"))
    mainClass.set("com.myapp.MainKt")
    buildNumber.set(System.currentTimeMillis())  // Or use CI build number
    platforms.set(listOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64"))
    privateKey.set(providers.environmentVariable("BUNDLE_PRIVATE_KEY"))

    dependsOn("installDist")
}

// Generate Ed25519 key pair for signing
tasks.register("generateBundleKeys") {
    doLast {
        val (privateKey, publicKey) = BundleCreatorTask.generateKeyPair()
        println("Private key (keep secret!): $privateKey")
        println("Public key (embed in shell): $publicKey")
    }
}
```

#### BundleCreatorTask Properties

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `inputDirectory` | `DirectoryProperty` | Yes | - | Source files to bundle |
| `outputDirectory` | `DirectoryProperty` | Yes | - | Output for manifest.json, zips/, files/ |
| `mainClass` | `Property<String>` | Yes | - | Fully qualified main class |
| `platforms` | `ListProperty<String>` | Yes | - | Target platforms (e.g., "macos-arm64", "windows-x64") |
| `buildNumber` | `Property<Long>` | Yes | - | Build number (set by CI) |
| `minShellVersion` | `Property<Int>` | No | 1 | Minimum shell version required |
| `shellUpdateUrl` | `Property<String>` | No | null | URL for shell updates |
| `privateKey` | `Property<String>` | One required | - | Base64-encoded private key (preferred for CI) |
| `privateKeyEnvVar` | `Property<String>` | One required | - | Environment variable name containing private key |
| `privateKeyFile` | `RegularFileProperty` | One required | - | File containing private key |

### Creating Bundles Programmatically

```kotlin
import io.runwork.bundle.creator.BundleManifestBuilder
import io.runwork.bundle.creator.BundleManifestSigner
import io.runwork.bundle.creator.BundlePackager

// Generate or load keys
val (privateKey, publicKey) = BundleManifestSigner.generateKeyPair()
val signer = BundleManifestSigner.fromBase64(privateKey)

val builder = BundleManifestBuilder()
val packager = BundlePackager()

// Specify target platforms
val targetPlatforms = listOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64")

// Collect files with platform constraints from resources/ folder structure
val bundleFiles = builder.collectFilesWithPlatformConstraints(inputDir)

// Package per-platform zips (deduplicated by content fingerprint) and individual files
val zips = packager.packageBundle(inputDir, outputDir, bundleFiles, targetPlatforms)

// Build unsigned manifest
val manifest = builder.build(
    inputDir = inputDir,
    buildNumber = System.currentTimeMillis(),
    mainClass = "com.example.MainKt",
    minShellVersion = 1,
    zips = zips,
)

// Sign and write
val signedManifest = signer.signManifest(manifest)
```

## Bundle Output Structure

When you create a bundle, it produces:

```
output/
├── manifest.json    # Signed manifest with file hashes and per-platform bundle info
├── zips/            # Per-platform bundle archives (deduplicated by content fingerprint)
│   ├── {fingerprint}.zip   # Platforms with identical content share the same zip
│   └── {fingerprint}.zip
└── files/           # Individual files named by SHA-256 hash
    ├── abc123...    # For incremental updates
    └── def456...
```

## License

Apache License 2.0
