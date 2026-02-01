# Bundle

[![Maven Central](https://img.shields.io/maven-central/v/io.runwork/bundle-bootstrap?color=blue)](https://central.sonatype.com/artifact/io.runwork/bundle-bootstrap)

A Kotlin library for managing versioned, signed software bundles with content-addressable storage.

## Features

- **Content-Addressable Storage**: Files stored by SHA-256 hash for deduplication
- **Incremental Updates**: Smart strategy choosing between full bundle or individual file downloads
- **Ed25519 Signatures**: Manifest integrity verified with Ed25519 cryptography
- **Isolated Classloaders**: Bundles load into custom classloaders for isolation
- **Cross-Platform**: Supports macOS, Linux, and Windows
- **Type-Safe Platform API**: Platform detection via `Os` and `Architecture` enums

## Artifacts

All artifacts are published to Maven Central under the `io.runwork` group:

| Artifact | Description | Use Case |
|----------|-------------|----------|
| `bundle-common` | Shared data classes, verification, and CAS | Dependency of all other modules |
| `bundle-bootstrap` | Validate and launch bundles | Shell application |
| `bundle-updater` | Download bundles (initial + updates) | Shell application AND inside bundle |
| `bundle-creator` | Create and sign bundles (library) | CI pipelines |
| `bundle-creator-gradle-task` | Gradle task for bundle creation | Gradle-based CI pipelines |

### Dependency Setup

Add artifacts to your `build.gradle.kts` based on your use case:

```kotlin
dependencies {
    // For shell applications (validates and launches bundles)
    implementation("io.runwork:bundle-bootstrap:<version>")
    implementation("io.runwork:bundle-updater:<version>")

    // For applications inside bundles (self-update capability)
    implementation("io.runwork:bundle-updater:<version>")

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
Architecture.ARM64   // Apple Silicon, ARM64
Architecture.X86_64  // Intel/AMD 64-bit

// Combine them or use auto-detection
val platform = Platform(Os.MACOS, Architecture.ARM64)
val currentPlatform = Platform.current  // Detects from system properties

// Convert to/from string format (for manifests)
val platformStr = platform.toString()          // "macos-arm64"
val parsed = Platform.fromString("linux-arm64") // Platform(Os.LINUX, Architecture.ARM64)
```

## Usage

### Shell Application: Validate and Launch Bundles

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
| `outputDirectory` | `DirectoryProperty` | Yes | - | Output for manifest.json, bundle.zip, files/ |
| `mainClass` | `Property<String>` | Yes | - | Fully qualified main class |
| `platform` | `Property<String>` | No | Auto-detect | Platform ID (e.g., "macos-arm64") |
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

// Build the bundle
val packager = BundlePackager()
val builder = BundleManifestBuilder()

val bundleFiles = builder.collectFiles(inputDir)
val bundleHash = packager.packageBundle(inputDir, outputDir, bundleFiles)

val manifest = builder.build(
    inputDir = inputDir,
    platform = "macos-arm64",
    buildNumber = System.currentTimeMillis(),
    mainClass = "com.example.MainKt",
    minShellVersion = 1,
    bundleHash = bundleHash,
)

val signedManifest = signer.signManifest(manifest)
```

## Bundle Output Structure

When you create a bundle, it produces:

```
output/
├── manifest.json    # Signed manifest with file hashes and metadata
├── bundle.zip       # Full bundle archive for initial downloads
└── files/           # Individual files named by SHA-256 hash
    ├── abc123...    # For incremental updates
    └── def456...
```

## License

Apache License 2.0
