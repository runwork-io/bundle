# CLAUDE.md

This file provides guidance for AI agents working with this codebase.

## Project Overview

**Bundle** is a Kotlin library for managing versioned, signed software bundles with content-addressable storage. It enables applications to deliver, update, and load isolated application bundles with cryptographic verification.

Key capabilities:
- Content-addressable storage (SHA-256 hashing) with deduplication
- Ed25519 cryptographic signatures on manifests
- Smart download strategy selection (incremental vs full)
- Isolated classloaders for bundle execution
- Cross-platform support (macOS, Linux, Windows)

## Published Artifacts

All artifacts are published to Maven Central under the `io.runwork` group:

| Artifact | Description | Use Case |
|----------|-------------|----------|
| `bundle-common` | Shared data classes, verification, and CAS | Dependency of all other modules |
| `bundle-bootstrap` | Validate and launch bundles | Shell application |
| `bundle-updater` | Download bundles (initial + updates) | Shell application AND inside bundle |
| `bundle-creator` | Create and sign bundles (library + CLI) | CI pipelines |
| `bundle-creator-gradle-task` | Gradle task for bundle creation | Gradle-based CI pipelines |

## Module Architecture

The system is split into five modules with clear responsibilities:

| Module | Purpose | Ships With |
|--------|---------|------------|
| **bundle-common** | Shared data classes, verification, CAS | All modules |
| **bundle-creator** | Create and sign bundles | CI only |
| **bundle-creator-gradle-task** | Gradle task wrapping bundle-creator | CI only (Gradle builds) |
| **bundle-bootstrap** | Validate and launch bundles | Shell app |
| **bundle-updater** | Download bundles (initial + updates) | Shell app AND inside bundle |

### Module Dependency Graph

```
                       bundle-common
                      /      |      \
                     /       |       \
     bundle-creator    bundle-bootstrap    bundle-updater
          |                  |                  |
          |                  |                  |
 bundle-creator-gradle-task   └──────┬───────────┘
          (CI)                      |
                               Shell app
                            (bootstrap + updater)

                             Also: bundle-updater
                             ships inside bundle
                             for self-updates
```

## Build & Test Commands

```bash
# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :bundle-common:test
./gradlew :bundle-bootstrap:test
./gradlew :bundle-updater:test
./gradlew :bundle-creator:test
./gradlew :bundle-creator-gradle-task:test

# Run a specific test class
./gradlew :bundle-updater:test --tests "io.runwork.bundle.updater.storage.StorageManagerTest"

# Build the project
./gradlew build

# Run the CLI (bundle creator)
./gradlew :bundle-creator:run --args="create --help"
```

## Project Structure

```
bundle/
├── build.gradle.kts                    # Root build config
├── bundle-common/                      # Shared code
│   └── src/main/kotlin/io/runwork/bundle/common/
│       ├── manifest/
│       │   ├── BundleManifest.kt       # Data model
│       │   └── BundleFile.kt           # File entry
│       ├── verification/
│       │   ├── SignatureVerifier.kt    # Ed25519 verify (NOT sign)
│       │   └── HashVerifier.kt         # SHA-256 hashing
│       ├── storage/
│       │   └── ContentAddressableStore.kt
│       └── BundleLaunchConfig.kt       # Config passed to bundle main()
│
├── bundle-bootstrap/                   # Validation and launch
│   └── src/main/kotlin/io/runwork/bundle/bootstrap/
│       ├── BundleBootstrap.kt          # Main orchestrator (validate/launch)
│       ├── BundleBootstrapConfig.kt    # Shell-provided config
│       ├── BundleValidationResult.kt   # Validation outcomes
│       └── loader/
│           ├── BundleClassLoader.kt    # Child-first classloader
│           └── LoadedBundle.kt         # Launched bundle handle
│
├── bundle-updater/                     # Download and update
│   └── src/main/kotlin/io/runwork/bundle/updater/
│       ├── BundleUpdater.kt            # Main API (one-shot + background)
│       ├── BundleUpdaterConfig.kt      # Runtime config
│       ├── BundleUpdateEvent.kt        # Update events
│       ├── download/
│       │   ├── DownloadManager.kt      # HTTP client, file downloads
│       │   ├── DownloadProgress.kt     # Progress reporting
│       │   └── UpdateDecider.kt        # Full vs incremental strategy
│       └── storage/
│           ├── StorageManager.kt       # Version directory management
│           └── CleanupManager.kt       # Old version & orphan CAS cleanup
│
├── bundle-creator/                     # CI tooling (library + CLI)
│   └── src/main/kotlin/io/runwork/bundle/creator/
│       ├── BundleManifestSigner.kt     # Ed25519 signing
│       ├── BundlePackager.kt           # Creates bundle.zip
│       ├── BundleManifestBuilder.kt    # Builds manifest from directory
│       └── cli/
│           └── BundleCreatorCli.kt     # CLI entry point
│
└── bundle-creator-gradle-task/          # Gradle integration
    └── src/main/kotlin/io/runwork/bundle/gradle/
        └── BundleCreatorTask.kt        # Gradle task for bundle creation
```

## Key Classes by Module

### bundle-common
| Class | Purpose |
|-------|---------|
| `BundleManifest` | Core data model for bundle metadata |
| `ContentAddressableStore` | Hash-based storage - store(), contains(), getPath() |
| `HashVerifier` | SHA-256 computation using Okio |
| `SignatureVerifier` | Ed25519 verification using JDK built-in |
| `BundleLaunchConfig` | Config passed from shell to bundle's main() |

### bundle-bootstrap
| Class | Purpose |
|-------|---------|
| `BundleBootstrap` | Main orchestrator - validate() then launch() |
| `BundleClassLoader` | Child-first classloader for bundle isolation |
| `LoadedBundle` | Handle to a running bundle with message bridge |

### bundle-updater
| Class | Purpose |
|-------|---------|
| `BundleUpdater` | Main API - downloadLatest() for shell, start() for background updates |
| `StorageManager` | Version lifecycle - prepareVersion(), getCurrentBuildNumber() |
| `DownloadManager` | HTTP downloads - downloadBundle(), fetchManifest() |
| `UpdateDecider` | Strategy selection - FullBundle, Incremental, or NoDownloadNeeded |
| `CleanupManager` | Removes old versions and orphaned CAS files |

### bundle-creator
| Class | Purpose |
|-------|---------|
| `BundleManifestSigner` | Ed25519 signing of manifests |
| `BundleManifestBuilder` | Builds manifest from directory contents |
| `BundlePackager` | Creates bundle.zip from directory |

### bundle-creator-gradle-task
| Class | Purpose |
|-------|---------|
| `BundleCreatorTask` | Gradle task that wraps bundle-creator for easy CI integration |

#### BundleCreatorTask Properties

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `inputDirectory` | `DirectoryProperty` | Yes | - | Source files to bundle |
| `outputDirectory` | `DirectoryProperty` | Yes | - | Output for manifest.json, bundle.zip, files/ |
| `mainClass` | `Property<String>` | Yes | - | Fully qualified main class |
| `platform` | `Property<String>` | No | Auto-detect | Platform ID (e.g., "macos-arm64") |
| `buildNumber` | `Property<Long>` | No | `System.currentTimeMillis()` | Build number |
| `minShellVersion` | `Property<Int>` | No | 1 | Minimum shell version required |
| `shellUpdateUrl` | `Property<String>` | No | null | URL for shell updates |
| `privateKey` | `Property<String>` | One required | - | Base64-encoded private key (preferred for CI) |
| `privateKeyEnvVar` | `Property<String>` | One required | - | Environment variable name containing private key |
| `privateKeyFile` | `RegularFileProperty` | One required | - | File containing private key |

## Storage Layout

```
appDataDir/
├── manifest.json              # Current manifest (single source of truth for version)
├── cas/                       # Content-addressable store
│   └── {sha256-hash}          #   Files named by hash, immutable once written
├── versions/
│   └── {buildNumber}/
│       └── {files...}         # Linked from CAS (symlinks on macOS/Linux, hard links on Windows)
└── temp/                      # Download staging (incomplete downloads)
```

Version completeness is guaranteed by `manifest.json`: it is only saved after `prepareVersion()` completes successfully within the same mutex-protected block. If `manifest.json` exists and points to version N, that version is fully prepared.

## Key Patterns & Conventions

### Coroutines
- All I/O operations use `withContext(Dispatchers.IO)`
- Public API methods are `suspend` functions
- Tests use `kotlinx.coroutines.test.runTest`

### Kotlin Style
- Prefer `listOf()` over `emptyList()` for empty lists
- Prefer `mapOf()` over `emptyMap()` for empty maps
- Prefer `setOf()` over `emptySet()` for empty sets

### Hash & Signature Format
- Hashes: Always prefixed with `sha256:` (e.g., `sha256:abc123...`)
- Signatures: Always prefixed with `ed25519:`
- Use `HashVerifier.computeHash()` for consistency

### Error Handling
- Retry logic with exponential backoff for network failures
- Sealed classes for result types (`UpdateCheckResult`, `BundleValidationResult`, etc.)
- Signature failures are retried (could be CDN corruption)

### Platform Conventions
- Platform IDs: `{os}-{arch}` format (e.g., `macos-arm64`, `windows-x86_64`, `linux-x86_64`)
- Paths in manifests use forward slashes (normalized on Windows)
- Version numbers are `Long` values (monotonically increasing)

## Critical Design Rules

### Update-First, Cleanup-Last Strategy
**Never delete files until we're fully up-to-date.**

Cleanup runs ONLY when:
1. We checked for updates from the server
2. No update is available (current version == latest)
3. Current version is valid and complete

This ensures interrupted downloads preserve partial progress in CAS.

### Downgrade Attack Prevention
All download paths must check `buildNumber > current`:
```kotlin
if (manifest.buildNumber <= currentBuild) {
    return DownloadResult.AlreadyUpToDate  // Reject downgrade
}
```
Use `<=` not `<` to prevent replay of same version.

### Concurrency Protection
StorageManager uses a mutex for all write operations:
```kotlin
suspend fun <T> withStorageLock(block: suspend () -> T): T {
    return storageMutex.withLock { block() }
}
```

### CAS Immutability
- Files in CAS are immutable (named by hash)
- If file exists in CAS, it's valid (hash verified on store)
- Partial downloads go to temp, only move to CAS when complete

## Data Flow

### Shell Startup (no bundle)
1. `BundleBootstrap.validate()` → returns `NoBundleExists`
2. `Updater.downloadLatest()` → fetches manifest, downloads files to CAS, prepares version
3. `BundleBootstrap.validate()` → returns `Valid`
4. `BundleBootstrap.launch()` → creates classloader, invokes main()

### Shell Startup (bundle exists)
1. `BundleBootstrap.validate()` → verifies signature and file hashes → returns `Valid`
2. `BundleBootstrap.launch()` → creates classloader, invokes main()

### Bundle Self-Update (runtime)
1. `Updater.start(callbacks)` → starts background coroutine
2. Periodically fetches manifest, compares buildNumber
3. If newer: downloads files, prepares version, calls `onUpdateReady()`
4. Bundle calls `restartApplication()` to apply update

## Testing

- Uses `MockWebServer` for HTTP testing
- `TestFixtures` object provides utilities for creating test data
- All async tests wrapped in `runTest { }`
- **JUnit is configured with a 20-second default timeout for all tests** (via `junit.jupiter.execution.timeout.default` in root `build.gradle.kts`)
- **Never use `Thread.sleep()` or fixed `delay()` in tests** - tests should use proper synchronization primitives (e.g., `CompletableDeferred`, `Channel`, `Mutex`, `CountDownLatch`) or event-based approaches to avoid brittleness
- CI runs on Ubuntu and Windows with JDK 17
- `bundle-creator-gradle-task` uses Gradle TestKit for functional testing

## Dependencies

- `kotlinx-coroutines-core` - Async operations
- `kotlinx-serialization-json` - JSON serialization
- `okio` - Efficient I/O and hashing
- `okhttp3` - HTTP client (bundle-updater only)
- `gradleApi()` - Gradle API (bundle-creator-gradle-task only, compileOnly)
- `gradleTestKit()` - Gradle TestKit (bundle-creator-gradle-task tests only)

## Common Tasks

### Modifying Download Strategy
Look at `UpdateDecider.decide()` which calculates effective cost:
- Full bundle: `totalSize`
- Incremental: `missingSize + (numMissingFiles * REQUEST_OVERHEAD)`

### Adding New Verification
1. Add verification logic to `StorageManager.verifyVersion()` or create new verifier
2. Integrate into `BundleBootstrap.validate()`
3. Add appropriate test coverage

### Using BundleCreatorTask in Consumer Projects

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
    privateKey.set(providers.environmentVariable("BUNDLE_PRIVATE_KEY"))
    dependsOn("installDist")
}

// Key generation helper
tasks.register("generateBundleKeys") {
    doLast {
        val (privateKey, publicKey) = BundleCreatorTask.generateKeyPair()
        println("Private key: $privateKey")
        println("Public key: $publicKey")
    }
}
```
