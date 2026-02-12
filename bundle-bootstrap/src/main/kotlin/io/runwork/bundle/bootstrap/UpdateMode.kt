package io.runwork.bundle.bootstrap

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Controls how [BundleBootstrap.validateAndLaunch] handles updates.
 */
sealed class UpdateMode {
    /** Always check server before launching. Fails if network unavailable. */
    data object RequireLatest : UpdateMode()

    /** Check server before launching. Falls back to existing bundle if network unavailable. */
    data object CheckOnLaunch : UpdateMode()

    /** Only download when no bundle exists. Bundle manages its own updates. (Default) */
    data object Manual : UpdateMode()

    /** Same as Manual for initial launch, then starts background updates and notifies bundle. */
    data class Background(val checkInterval: Duration = 6.hours) : UpdateMode()
}
