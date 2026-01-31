package io.runwork.bundle.common.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Type of file in the bundle, used for special handling during loading.
 */
@Serializable
enum class FileType {
    /** Java archive - loaded into classloader */
    @SerialName("jar")
    JAR,

    /** Native library (.dylib, .so, .dll) - added to java.library.path */
    @SerialName("native")
    NATIVE,

    /** General resource file (fonts, images, configs) */
    @SerialName("resource")
    RESOURCE,

    /** Executable binary (e.g., bun) */
    @SerialName("executable")
    EXECUTABLE,
}
