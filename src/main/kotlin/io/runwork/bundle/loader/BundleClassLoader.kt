package io.runwork.bundle.loader

import java.net.URL
import java.net.URLClassLoader

/**
 * Child-first classloader for bundle isolation.
 *
 * This classloader loads classes from the bundle first, falling back to the
 * parent only for JDK classes. This ensures that the bundle can use different
 * versions of libraries than the shell, preventing ClassLoader conflicts.
 */
class BundleClassLoader(
    urls: Array<URL>,
    parent: ClassLoader
) : URLClassLoader(urls, parent) {

    companion object {
        /** Packages that should always come from parent (JDK classes). */
        private val PARENT_PACKAGES = listOf(
            "java.",
            "javax.",
            "sun.",
            "jdk.",
        )

        /**
         * Check if a class should be loaded from the parent classloader.
         */
        private fun shouldLoadFromParent(name: String): Boolean {
            return PARENT_PACKAGES.any { name.startsWith(it) }
        }
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            // Check if already loaded
            var c = findLoadedClass(name)
            if (c != null) {
                if (resolve) resolveClass(c)
                return c
            }

            // Parent-first for JDK and bridge classes
            if (shouldLoadFromParent(name)) {
                return super.loadClass(name, resolve)
            }

            // Child-first: try to load from bundle
            try {
                c = findClass(name)
                if (resolve) resolveClass(c)
                return c
            } catch (e: ClassNotFoundException) {
                // Fall back to parent
                return super.loadClass(name, resolve)
            }
        }
    }

    override fun getResource(name: String): URL? {
        // Child-first for resources too
        return findResource(name) ?: super.getResource(name)
    }

    override fun getResources(name: String): java.util.Enumeration<URL> {
        // Combine child and parent resources, child first
        val childResources = findResources(name).toList()
        val parentResources = parent?.getResources(name)?.toList() ?: emptyList()
        return java.util.Collections.enumeration(childResources + parentResources)
    }
}
