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

## Module Architecture

The system is split into four modules with clear responsibilities:

| Module | Purpose | Ships With |
|--------|---------|------------|
| **bundle-common** | Shared data classes, verification, CAS | All modules |
| **bundle-creator** | Create and sign bundles | CI only |
| **bundle-bootstrap** | Validate and launch bundles | Shell app |
| **bundle-updater** | Download bundles (initial + updates) | Shell app AND inside bundle |

### Module Dependency Graph

```
                       bundle-common
                      /      |      \
                     /       |       \
     bundle-creator    bundle-bootstrap    bundle-updater
          (CI)              |                  |
                            |                  |
                            └──────┬───────────┘
                                   |
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
│       │   ├── BundleFile.kt           # File entry
│       │   └── FileType.kt             # JAR, NATIVE, RESOURCE, EXECUTABLE
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
└── bundle-creator/                     # CI tooling
    └── src/main/kotlin/io/runwork/bundle/creator/
        ├── BundleManifestSigner.kt     # Ed25519 signing
        ├── BundlePackager.kt           # Creates bundle.zip
        ├── BundleManifestBuilder.kt    # Builds manifest from directory
        └── cli/
            └── BundleCreatorCli.kt     # CLI entry point
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
| `StorageManager` | Version lifecycle - prepareVersion(), setCurrentVersion() |
| `DownloadManager` | HTTP downloads - downloadBundle(), fetchManifest() |
| `UpdateDecider` | Strategy selection - FullBundle, Incremental, or NoDownloadNeeded |
| `CleanupManager` | Removes old versions and orphaned CAS files |

### bundle-creator
| Class | Purpose |
|-------|---------|
| `BundleManifestSigner` | Ed25519 signing of manifests |
| `BundleManifestBuilder` | Builds manifest from directory contents |
| `BundlePackager` | Creates bundle.zip from directory |

## Storage Layout

```
appDataDir/
├── manifest.json              # Current manifest (points to active version)
├── current                    # Symlink (Unix) or text file (Windows) → version dir
├── cas/                       # Content-addressable store
│   └── {sha256-hash}          #   Files named by hash, immutable once written
├── versions/
│   └── {buildNumber}/
│       ├── .complete          # Marker file (version is fully prepared)
│       └── {files...}         # Hard-linked from CAS
└── temp/                      # Download staging (incomplete downloads)
```

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

## Dependencies

- `kotlinx-coroutines-core` - Async operations
- `kotlinx-serialization-json` - JSON serialization
- `okio` - Efficient I/O and hashing
- `okhttp3` - HTTP client (bundle-updater only)

## Common Tasks

### Adding a New File Type
1. Add to `FileType` enum in `bundle-common/manifest/FileType.kt`
2. Update `BundleClassLoader` if special handling needed
3. Update CLI if needed for bundle creation

### Modifying Download Strategy
Look at `UpdateDecider.decide()` which calculates effective cost:
- Full bundle: `totalSize`
- Incremental: `missingSize + (numMissingFiles * REQUEST_OVERHEAD)`

### Adding New Verification
1. Add verification logic to `StorageManager.verifyVersion()` or create new verifier
2. Integrate into `BundleBootstrap.validate()`
3. Add appropriate test coverage
