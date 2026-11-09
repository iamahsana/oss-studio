package com.iamahsana.ossstudio.scene.editor

import kotlin.math.roundToInt

/** Returns a non-negative canvas coordinate aligned to a logical-pixel grid. */
internal fun snapSceneCoordinate(value: Float, gridSize: Float = DEFAULT_GRID_SIZE): Float {
    require(value.isFinite() && value >= 0f) { "Coordinate must be finite and non-negative" }
    require(gridSize.isFinite() && gridSize > 0f) { "Grid size must be finite and positive" }
    return (value / gridSize).roundToInt() * gridSize
}

internal fun clampSceneCoordinate(value: Float, canvasExtent: Float, elementExtent: Float): Float {
    require(value.isFinite() && canvasExtent.isFinite() && elementExtent.isFinite())
    require(canvasExtent > 0f && elementExtent > 0f)
    return value.coerceIn(0f, (canvasExtent - elementExtent).coerceAtLeast(0f))
}

internal const val DEFAULT_GRID_SIZE = 24f
