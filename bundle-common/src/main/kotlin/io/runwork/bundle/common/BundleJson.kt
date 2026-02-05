package io.runwork.bundle.common

import kotlinx.serialization.json.Json

/**
 * Shared, explicitly-configured [Json] instance for bundle signing and verification.
 *
 * Both signing ([io.runwork.bundle.creator.BundleManifestSigner]) and verification
 * ([io.runwork.bundle.common.verification.SignatureVerifier]) serialize a [BundleManifest]
 * to JSON before computing/checking the Ed25519 signature. They MUST produce identical
 * output. This object pins the exact configuration so a library upgrade or accidental
 * use of a differently-configured instance cannot silently break signature verification.
 */
object BundleJson {
    /**
     * Json instance used for encoding manifests before signing/verification.
     *
     * Configuration rationale:
     * - [encodeDefaults] = false  — omits fields that equal their default value
     *   (e.g. `signature = ""`, `shellUpdateUrl = null`), matching the format
     *   that has been in production since launch.
     * - [prettyPrint] = false — compact single-line output (the signed form).
     * - [explicitNulls] = true — if a nullable field is explicitly set to `null`
     *   **and** that is not its default, it will appear in the output.
     */
    val signingJson: Json = Json {
        encodeDefaults = false
        prettyPrint = false
        explicitNulls = true
    }
}
