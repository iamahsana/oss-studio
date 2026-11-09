package com.iamahsana.ossstudio.scene.model

import java.util.UUID

enum class CanvasOrientation { PORTRAIT, LANDSCAPE }

enum class SceneSourceType {
    TEXT, IMAGE, GIF, VIDEO, SHARE_SCREEN, CAMERA, USB_VIDEO
}

data class CanvasSize(
    val width: Int,
    val height: Int,
    val orientation: CanvasOrientation
) {
    init {
        require(width > 0 && height > 0) { "Canvas dimensions must be positive" }
        require(
            when (orientation) {
                CanvasOrientation.PORTRAIT -> height >= width
                CanvasOrientation.LANDSCAPE -> width >= height
            }
        ) { "Canvas dimensions do not match the selected orientation" }
    }

    companion object {
        val DEFAULT_PORTRAIT = CanvasSize(1080, 1920, CanvasOrientation.PORTRAIT)
        val DEFAULT_LANDSCAPE = CanvasSize(1920, 1080, CanvasOrientation.LANDSCAPE)
    }
}

data class SceneProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val activeSceneId: String? = null,
    val scenes: List<Scene> = emptyList(),
    val sources: List<SceneSource> = emptyList(),
    val assets: List<SceneAsset> = emptyList(),
    val version: Int = CURRENT_VERSION
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(createdAtEpochMillis >= 0L)
        require(updatedAtEpochMillis >= createdAtEpochMillis)
        require(version > 0)
        require(scenes.map { it.id }.distinct().size == scenes.size) {
            "Scene IDs must be unique within a project"
        }
        require(scenes.all { it.projectId == id }) {
            "Every scene must belong to this project"
        }
        require(sources.map { it.id }.distinct().size == sources.size) {
            "Source IDs must be unique within a project"
        }
        require(assets.map { it.id }.distinct().size == assets.size) {
            "Asset IDs must be unique within a project"
        }
        require(sources.all { source -> source.assetId == null || assets.any { it.id == source.assetId } }) {
            "Every source asset reference must resolve within this project"
        }
        val sourceIds = sources.mapTo(mutableSetOf()) { it.id }
        require(scenes.all { scene -> scene.elements.all { it.sourceId in sourceIds } }) {
            "Every scene element must reference a source in this project"
        }
        require(activeSceneId == null || scenes.any { it.id == activeSceneId }) {
            "Active scene must belong to this project"
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val name: String,
    val canvas: CanvasSize = CanvasSize.DEFAULT_PORTRAIT,
    val backgroundArgb: Long = 0xFF000000,
    val elements: List<SceneElement> = emptyList()
) {
    init {
        require(id.isNotBlank())
        require(projectId.isNotBlank())
        require(name.isNotBlank())
        require(elements.map { it.id }.distinct().size == elements.size) {
            "Element IDs must be unique within a scene"
        }
        require(elements.map { it.zIndex }.distinct().size == elements.size) {
            "Element z-index values must be unique within a scene"
        }
    }
}

data class SceneElement(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val transform: ElementTransform = ElementTransform(),
    val crop: CropRect = CropRect(),
    val opacity: Float = 1f,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val zIndex: Int,
    val groupId: String? = null
) {
    init {
        require(id.isNotBlank())
        require(sourceId.isNotBlank())
        require(opacity in 0f..1f)
        require(groupId == null || groupId.isNotBlank())
    }
}

/**
 * Geometry is stored in logical canvas pixels. Editor zoom and screen density
 * must never be written into this model.
 */
data class ElementTransform(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 320f,
    val height: Float = 180f,
    val rotationDegrees: Float = 0f
) {
    init {
        require(x.isFinite() && y.isFinite())
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f)
        require(rotationDegrees.isFinite())
    }
}

data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left in 0f..1f && top in 0f..1f)
        require(right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top) { "Crop rectangle must have positive area" }
    }
}

data class SceneSource(
    val id: String = UUID.randomUUID().toString(),
    val type: SceneSourceType,
    val displayName: String,
    val enabled: Boolean = true,
    val assetId: String? = null,
    val text: String? = null,
    val settings: Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(assetId == null || assetId.isNotBlank())
        require(text == null || type == SceneSourceType.TEXT)
        require(assetId != null || type in setOf(SceneSourceType.TEXT, SceneSourceType.SHARE_SCREEN, SceneSourceType.CAMERA, SceneSourceType.USB_VIDEO))
    }
}

data class SceneAsset(
    val id: String = UUID.randomUUID().toString(),
    val relativePath: String,
    val mimeType: String,
    val byteSize: Long,
    val sha256: String,
    val license: String? = null,
    val provenance: String? = null
) {
    init {
        require(id.isNotBlank())
        require(isSafeRelativeAssetPath(relativePath)) { "Asset path must be a normalized project-relative path" }
        require(mimeType.contains("/"))
        require(byteSize >= 0L)
        require(sha256.matches(Regex("(?i)^[a-f0-9]{64}$")))
    }
}

/** Reject absolute paths, traversal, platform-specific separators and control characters. */
private fun isSafeRelativeAssetPath(path: String): Boolean {
    if (path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains('\\')) return false
    if (path.any { it.code < 0x20 || it.code == 0x7f }) return false
    if (Regex("^[A-Za-z]:").containsMatchIn(path)) return false
    val segments = path.split('/')
    return segments.all { it.isNotBlank() && it != "." && it != ".." }
}
