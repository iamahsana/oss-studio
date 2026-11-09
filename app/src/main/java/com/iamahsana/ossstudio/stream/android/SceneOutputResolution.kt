package com.iamahsana.ossstudio.stream.android

import com.iamahsana.ossstudio.scene.model.CanvasSize
import kotlin.math.max
import kotlin.math.roundToInt

data class SceneOutputResolution(val width: Int, val height: Int)

fun sceneOutputResolution(canvas: CanvasSize, maxDimension: Int = 1280): SceneOutputResolution {
    require(maxDimension >= 2)
    val maxSide = max(canvas.width, canvas.height).toFloat()
    val scale = maxDimension / maxSide
    fun evenDimension(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2
    return SceneOutputResolution(
        width = evenDimension((canvas.width * scale).roundToInt()),
        height = evenDimension((canvas.height * scale).roundToInt())
    )
}
