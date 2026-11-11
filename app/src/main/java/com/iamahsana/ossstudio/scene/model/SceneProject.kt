package com.iamahsana.ossstudio.scene.model

import java.util.UUID

enum class CanvasOrientation { PORTRAIT, LANDSCAPE }

enum class SceneSourceType {
    TEXT, IMAGE, GIF, VIDEO, SHARE_SCREEN, CAMERA, USB_VIDEO, EXTENSION
}

data class CanvasSize(
    val width: Int,
    val height: Int,
    val orientation: CanvasOrientation
) {
    init {
        require(width in MIN_DIMENSION..MAX_DIMENSION && height in MIN_DIMENSION..MAX_DIMENSION) {
            "Canvas dimensions must be between $MIN_DIMENSION and $MAX_DIMENSION pixels"
        }
        require(
            when (orientation) {
                CanvasOrientation.PORTRAIT -> height >= width
                CanvasOrientation.LANDSCAPE -> width >= height
            }
        ) { "Canvas dimensions do not match the selected orientation" }
    }

    companion object {
        const val MIN_DIMENSION = 64
        const val MAX_DIMENSION = 4096

        val DEFAULT_PORTRAIT = CanvasSize(1080, 1920, CanvasOrientation.PORTRAIT)
        val DEFAULT_LANDSCAPE = CanvasSize(1920, 1080, CanvasOrientation.LANDSCAPE)
        val SQUARE = CanvasSize(1080, 1080, CanvasOrientation.PORTRAIT)
        val PORTRAIT_4_5 = CanvasSize(1080, 1350, CanvasOrientation.PORTRAIT)
        val PORTRAIT_3_4 = CanvasSize(1080, 1440, CanvasOrientation.PORTRAIT)
        val LANDSCAPE_4_3 = CanvasSize(1440, 1080, CanvasOrientation.LANDSCAPE)
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
    val settings: Map<String, String> = emptyMap(),
    /** Stable package identifier; null for built-in Core source types. */
    val extensionId: String? = null,
    /** Stable namespaced element/source type identifier owned by the extension contract. */
    val typeId: String? = null
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(assetId == null || assetId.isNotBlank())
        require(text == null || type == SceneSourceType.TEXT)
        require(assetId != null || type in setOf(SceneSourceType.TEXT, SceneSourceType.SHARE_SCREEN, SceneSourceType.CAMERA, SceneSourceType.USB_VIDEO, SceneSourceType.EXTENSION))
        if (type == SceneSourceType.EXTENSION) {
            require(extensionId != null && NAMESPACED_EXTENSION_ID.matches(extensionId)) {
                "Extension sources require a valid namespaced extensionId"
            }
            require(typeId != null && NAMESPACED_TYPE_ID.matches(typeId)) {
                "Extension sources require a valid namespaced typeId"
            }
        } else {
            require(extensionId == null && typeId == null) {
                "Built-in source types cannot carry extension identifiers"
            }
        }
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

private val NAMESPACED_EXTENSION_ID = Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+){1,5}$")
private val NAMESPACED_TYPE_ID = Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+){1,7}$")

/** Axis-aligned canvas bounds occupied by an element after rotation around its center. */
data class ElementBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Returns the axis-aligned bounding box of this rotated element in canvas pixels.
 * The element's stored x/y remain the unrotated top-left anchor used by rendering.
 */
fun ElementTransform.rotatedBounds(): ElementBounds {
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val cosine = kotlin.math.abs(kotlin.math.cos(radians)).toFloat()
    val sine = kotlin.math.abs(kotlin.math.sin(radians)).toFloat()
    val halfWidth = (width * cosine + height * sine) / 2f
    val halfHeight = (width * sine + height * cosine) / 2f
    val centerX = x + width / 2f
    val centerY = y + height / 2f
    return ElementBounds(
        left = centerX - halfWidth,
        top = centerY - halfHeight,
        right = centerX + halfWidth,
        bottom = centerY + halfHeight
    )
}

/**
 * Moves an element so its rotated visual footprint fits inside the canvas.
 * If the rotated footprint is larger than an axis, that axis is centered.
 */
fun ElementTransform.clampedToCanvas(canvas: CanvasSize): ElementTransform {
    val bounds = rotatedBounds()
    val deltaX = when {
        bounds.width > canvas.width -> canvas.width / 2f - (bounds.left + bounds.right) / 2f
        bounds.left < 0f -> -bounds.left
        bounds.right > canvas.width -> canvas.width - bounds.right
        else -> 0f
    }
    val deltaY = when {
        bounds.height > canvas.height -> canvas.height / 2f - (bounds.top + bounds.bottom) / 2f
        bounds.top < 0f -> -bounds.top
        bounds.bottom > canvas.height -> canvas.height - bounds.bottom
        else -> 0f
    }
    return copy(x = x + deltaX, y = y + deltaY)
}



enum class ElementBoundsAlignment { START, CENTER, END }

/**
 * Aligns elements by their rotated visual bounds, preserving each element's size and rotation.
 * The returned list keeps the same order as the input list.
 */
fun alignTransformsByRotatedBounds(
    transforms: List<ElementTransform>,
    horizontal: Boolean,
    alignment: ElementBoundsAlignment,
    canvas: CanvasSize
): List<ElementTransform> {
    if (transforms.isEmpty()) return transforms
    val bounds = transforms.map { it.rotatedBounds() }
    val start = if (horizontal) bounds.minOf { it.left } else bounds.minOf { it.top }
    val end = if (horizontal) bounds.maxOf { it.right } else bounds.maxOf { it.bottom }
    val center = (start + end) / 2f
    return transforms.mapIndexed { index, transform ->
        val current = bounds[index]
        val currentStart = if (horizontal) current.left else current.top
        val currentEnd = if (horizontal) current.right else current.bottom
        val delta = when (alignment) {
            ElementBoundsAlignment.START -> start - currentStart
            ElementBoundsAlignment.CENTER -> center - (currentStart + currentEnd) / 2f
            ElementBoundsAlignment.END -> end - currentEnd
        }
        (if (horizontal) transform.copy(x = transform.x + delta)
        else transform.copy(y = transform.y + delta)).clampedToCanvas(canvas)
    }
}

/**
 * Evenly distributes rotated visual bounds along one axis. Returns null when the
 * selected elements cannot fit without overlapping; input order is preserved.
 */
fun distributeTransformsByRotatedBounds(
    transforms: List<ElementTransform>,
    horizontal: Boolean,
    canvas: CanvasSize
): List<ElementTransform>? {
    if (transforms.size < 2) return transforms
    val ordered = transforms.mapIndexed { index, transform -> index to transform.rotatedBounds() }
        .sortedBy { (_, bounds) -> if (horizontal) bounds.left else bounds.top }
    val start = if (horizontal) ordered.minOf { it.second.left } else ordered.minOf { it.second.top }
    val end = if (horizontal) ordered.maxOf { it.second.right } else ordered.maxOf { it.second.bottom }
    val totalExtent = ordered.sumOf { (_, bounds) ->
        (if (horizontal) bounds.width else bounds.height).toDouble()
    }.toFloat()
    val gap = (end - start - totalExtent) / (ordered.size - 1)
    if (gap < -0.001f) return null
    val result = transforms.toMutableList()
    var cursor = start
    ordered.forEach { (index, bounds) ->
        val transform = transforms[index]
        val delta = cursor - (if (horizontal) bounds.left else bounds.top)
        result[index] = (if (horizontal) transform.copy(x = transform.x + delta)
        else transform.copy(y = transform.y + delta)).clampedToCanvas(canvas)
        cursor += (if (horizontal) bounds.width else bounds.height) + gap
    }
    return result
}
