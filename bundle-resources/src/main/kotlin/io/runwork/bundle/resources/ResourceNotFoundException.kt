package io.runwork.bundle.resources

import java.nio.file.Path

/**
 * Exception thrown when a required resource is not found in any of the searched locations.
 *
 * @property path The relative resource path that was requested
 * @property searchedLocations The absolute paths that were checked for the resource
 */
class ResourceNotFoundException(
    val path: String,
    val searchedLocations: List<Path>,
) : RuntimeException("Resource not found: $path. Searched: $searchedLocations")
