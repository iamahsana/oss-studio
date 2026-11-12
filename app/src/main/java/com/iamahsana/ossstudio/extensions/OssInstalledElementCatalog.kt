package com.iamahsana.ossstudio.extensions

import java.io.File

/**
 * A validated, installed declarative element. The package is revalidated when
 * the catalog is loaded; resource access is constrained to that package root.
 */
data class OssInstalledElement(
    val extensionId: String,
    val extensionName: String,
    val extensionVersion: String,
    val definition: OssPackElement,
    private val packageDirectory: File
) {
    val typeId: String get() = definition.typeId
    val name: String get() = definition.name
    val kind: String get() = definition.kind
    val defaults: Map<String, String> get() = definition.defaults

    /**
     * Returns null for resource-free elements or if a resource is no longer
     * present. Canonical-path checks prevent an installed package from
     * resolving files outside its own directory.
     */
    fun resolveResource(): File? {
        val relativePath = definition.resourcePath ?: return null
        return runCatching {
            val root = packageDirectory.canonicalFile
            val resource = File(root, relativePath).canonicalFile
            require(resource.path.startsWith(root.path + File.separator))
            require(resource.isFile)
            resource
        }.getOrNull()
    }
}

/**
 * Reads only verified installed packages. Invalid or partially modified
 * packages are omitted rather than exposed to the Scene Editor.
 *
 * This catalog does not execute extension code and does not render elements;
 * it provides the validated declarations and package-local resources to the
 * Core-owned editor/rendering pipeline.
 */
object OssInstalledElementCatalog {
    fun listInstalled(root: File): List<OssInstalledElement> {
        if (!root.isDirectory) return emptyList()

        return root.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .flatMap { directory ->
                val manifest = runCatching {
                    OssPackArchiveValidator.readInstalledManifest(directory)
                }.getOrNull() ?: return@flatMap emptySequence()

                manifest.elements.asSequence().map { element ->
                    OssInstalledElement(
                        extensionId = manifest.id,
                        extensionName = manifest.name,
                        extensionVersion = manifest.version,
                        definition = element,
                        packageDirectory = directory
                    )
                }
            }
            .sortedWith(
                compareBy<OssInstalledElement> { it.extensionName.lowercase() }
                    .thenBy { it.name.lowercase() }
            )
            .toList()
    }

    fun find(root: File, extensionId: String, typeId: String): OssInstalledElement? =
        listInstalled(root).firstOrNull {
            it.extensionId == extensionId && it.typeId == typeId
        }
}
