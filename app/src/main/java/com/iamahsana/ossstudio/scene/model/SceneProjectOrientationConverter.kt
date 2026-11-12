package com.iamahsana.ossstudio.scene.model

import kotlin.math.min

/**
 * Converts every scene in a project to the canonical LIVE canvas while
 * preserving the original composition's aspect ratio and relative geometry.
 *
 * Content is uniformly scaled to fit (never stretched or cropped) and
 * centered in the target canvas. Element crop, rotation, layer order,
 * visibility, locking, and group membership remain unchanged.
 */
object SceneProjectOrientationConverter {
    fun convert(project: SceneProject, orientation: CanvasOrientation): SceneProject {
        val target = when (orientation) {
            CanvasOrientation.PORTRAIT -> CanvasSize.DEFAULT_PORTRAIT
            CanvasOrientation.LANDSCAPE -> CanvasSize.DEFAULT_LANDSCAPE
        }

        val convertedScenes = project.scenes.map { scene ->
            val source = scene.canvas
            if (source == target) {
                scene
            } else {
                val scale = min(
                    target.width.toFloat() / source.width.toFloat(),
                    target.height.toFloat() / source.height.toFloat()
                )
                val contentWidth = source.width * scale
                val contentHeight = source.height * scale
                val offsetX = (target.width - contentWidth) / 2f
                val offsetY = (target.height - contentHeight) / 2f

                scene.copy(
                    canvas = target,
                    elements = scene.elements.map { element ->
                        element.copy(
                            transform = element.transform.copy(
                                x = element.transform.x * scale + offsetX,
                                y = element.transform.y * scale + offsetY,
                                width = element.transform.width * scale,
                                height = element.transform.height * scale
                            )
                        )
                    }
                )
            }
        }

        return project.copy(scenes = convertedScenes, liveOrientation = orientation)
    }
}
