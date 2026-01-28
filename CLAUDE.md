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

## Build & Test Commands

```bash
# Run all tests
./gradlew :bundle:test

# Run a specific test class
./gradlew :bundle:test --tests "io.runwork.bundle.BundleManagerTest"

# Build the project
./gradlew build

# Run the CLI
./gradlew :bundle:run --args="--help"
```

## Project Structure

```
bundle/
├── build.gradle.kts                    # Root build config
├── bundle/
│   ├── build.gradle.kts                # Module config (Java 17)
│   └── src/main/kotlin/io/runwork/bundle/
│       ├── BundleManager.kt            # Main orchestration class
│       ├── BundleConfig.kt             # Configuration data class
│       ├── cli/BundleCreatorCli.kt     # CLI for creating bundles
│       ├── download/
│       │   ├── DownloadManager.kt      # HTTP downloads via OkHttp
│       │   └── UpdateDecider.kt        # Strategy selection logic
│       ├── loader/
│       │   ├── BundleLoader.kt         # Loads bundles into classloaders
│       │   └── BundleClassLoader.kt    # Child-first classloader
│       ├── manifest/
│       │   ├── BundleManifest.kt       # Core data model
│       │   └── ManifestSigner.kt       # Ed25519 signing
│       ├── storage/
│       │   ├── StorageManager.kt       # Version/file management
│       │   └── ContentAddressableStore.kt  # Hash-based storage
│       └── verification/
│           ├── HashVerifier.kt         # SHA-256 hashing (Okio)
│           └── SignatureVerifier.kt    # Ed25519 verification
```

## Architecture

### Layered Design

1. **API Layer** (`BundleManager`): Main entry point for applications
2. **Download Layer** (`DownloadManager`, `UpdateDecider`): Smart download strategy
3. **Storage Layer** (`StorageManager`, `ContentAddressableStore`): File organization
4. **Verification Layer** (`HashVerifier`, `SignatureVerifier`): Integrity checking
5. **Loading Layer** (`BundleLoader`, `BundleClassLoader`): Bundle execution

### Content-Addressable Storage (CAS)

Files are stored by their SHA-256 hash, enabling:
- Deduplication across versions (same content = same hash = stored once)
- Hard-linking from version directories to CAS (efficient on Unix systems)
- Fallback to copying on Windows or cross-filesystem scenarios

### Directory Structure at Runtime

```
appDataDir/
├── current                  # Symlink (Unix) or text file (Windows) pointing to active version
├── manifest.json            # Current manifest
├── cas/                     # Content-addressable storage
│   └── <sha256-hash>        # Files named by their hash
├── versions/
│   └── <buildNumber>/
│       ├── .complete        # Marker file indicating successful preparation
│       └── <files>          # Hard-links to CAS
└── temp/                    # Temporary files during downloads
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
- Sealed classes for result types (`UpdateCheckResult`, `BundleVerificationResult`, etc.)
- Signature failures are retried (could be CDN corruption)

### Platform Conventions

- Platform IDs: `{os}-{arch}` format (e.g., `macos-arm64`, `windows-x86_64`, `linux-x86_64`)
- Paths in manifests use forward slashes (normalized on Windows)
- Version numbers are `Long` values (monotonically increasing)

## Main Classes

| Class | Purpose |
|-------|---------|
| `BundleManager` | Main orchestrator - checkForUpdate(), downloadUpdate(), loadBundle(), verifyLocalBundle() |
| `StorageManager` | Version lifecycle - prepareVersion(), setCurrentVersion(), verifyVersion() |
| `ContentAddressableStore` | Hash-based storage - store(), contains(), getPath() |
| `DownloadManager` | HTTP downloads - downloadBundle(), fetchManifest() |
| `UpdateDecider` | Strategy selection - returns FullBundle, Incremental, or NoDownloadNeeded |
| `BundleLoader` | Classloader creation - load() creates isolated classloader and invokes main |
| `HashVerifier` | SHA-256 computation using Okio |
| `SignatureVerifier` | Ed25519 verification using JDK built-in |

## Testing

- Uses `MockWebServer` for HTTP testing
- `TestFixtures` object provides utilities for creating test data
- All async tests wrapped in `runTest { }`
- CI runs on Ubuntu and Windows with JDK 17

## Dependencies

- `kotlinx-coroutines-core` - Async operations
- `kotlinx-serialization-json` - JSON serialization
- `okio` - Efficient I/O and hashing
- `okhttp3` - HTTP client

## Common Tasks

### Adding a New File Type

1. Add to `FileType` enum in `BundleManifest.kt`
2. Update `BundleLoader` if special handling needed
3. Update CLI if needed for bundle creation

### Modifying Download Strategy

Look at `UpdateDecider.decide()` which calculates effective cost:
- Full bundle: `totalSize`
- Incremental: `missingSize + (numMissingFiles * REQUEST_OVERHEAD)`

### Adding New Verification

1. Add verification logic to `StorageManager.verifyVersion()` or create new verifier
2. Integrate into `BundleManager.verifyLocalBundle()`
3. Add appropriate test coverage
