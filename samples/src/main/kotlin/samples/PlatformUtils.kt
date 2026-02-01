package samples

import io.runwork.bundle.common.Platform

/**
 * Detect the current platform for bundle downloads.
 */
fun detectPlatform(): Platform = Platform.current()
