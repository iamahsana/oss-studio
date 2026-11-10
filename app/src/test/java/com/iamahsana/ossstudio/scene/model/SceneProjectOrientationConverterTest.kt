package com.iamahsana.ossstudio.scene.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class SceneProjectOrientationConverterTest {
    @Test
    fun portraitToLandscapeFitsAndCentersWithoutStretchingOrCropping() {
        val element = SceneElement(
            id = "element",
            sourceId = "source",
            transform = ElementTransform(x = 100f, y = 200f, width = 320f, height = 180f, rotationDegrees = 12f),
            crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f),
            opacity = 0.7f,
            visible = false,
            locked = true,
            zIndex = 4,
            groupId = "group"
        )
        val scene = Scene(
            id = "scene",
            projectId = "project",
            name = "Portrait",
            canvas = CanvasSize.DEFAULT_PORTRAIT,
            elements = listOf(element)
        )
        val project = projectWith(scene)

        val converted = SceneProjectOrientationConverter.convert(project, CanvasOrientation.LANDSCAPE)
        val result = converted.scenes.single()
        val transform = result.elements.single().transform

        assertEquals(CanvasSize.DEFAULT_LANDSCAPE, result.canvas)
        assertEquals(712.5f, transform.x, 0.001f)
        assertEquals(112.5f, transform.y, 0.001f)
        assertEquals(180f, transform.width, 0.001f)
        assertEquals(101.25f, transform.height, 0.001f)
        assertEquals(12f, transform.rotationDegrees, 0.001f)
        assertEquals(element.crop, result.elements.single().crop)
        assertEquals(element.opacity, result.elements.single().opacity)
        assertEquals(element.visible, result.elements.single().visible)
        assertEquals(element.locked, result.elements.single().locked)
        assertEquals(element.zIndex, result.elements.single().zIndex)
        assertEquals(element.groupId, result.elements.single().groupId)
    }

    @Test
    fun landscapeToPortraitFitsAndCentersWithoutStretchingOrCropping() {
        val scene = Scene(
            id = "scene",
            projectId = "project",
            name = "Landscape",
            canvas = CanvasSize.DEFAULT_LANDSCAPE,
            elements = listOf(
                SceneElement(
                    id = "element",
                    sourceId = "source",
                    transform = ElementTransform(x = 200f, y = 100f, width = 400f, height = 200f),
                    zIndex = 0
                )
            )
        )

        val result = SceneProjectOrientationConverter.convert(
            projectWith(scene),
            CanvasOrientation.PORTRAIT
        ).scenes.single()

        assertEquals(CanvasSize.DEFAULT_PORTRAIT, result.canvas)
        assertEquals(112.5f, result.elements.single().transform.x, 0.001f)
        assertEquals(712.5f, result.elements.single().transform.y, 0.001f)
        assertEquals(225f, result.elements.single().transform.width, 0.001f)
        assertEquals(112.5f, result.elements.single().transform.height, 0.001f)
    }

    @Test
    fun convertsEverySceneIncludingLegacyCustomRatios() {
        val portrait = Scene(
            id = "portrait",
            projectId = "project",
            name = "Portrait",
            canvas = CanvasSize.PORTRAIT_4_5
        )
        val square = Scene(
            id = "square",
            projectId = "project",
            name = "Square",
            canvas = CanvasSize.SQUARE
        )
        val project = projectWith(portrait, square)

        val result = SceneProjectOrientationConverter.convert(project, CanvasOrientation.LANDSCAPE)

        assertEquals(2, result.scenes.size)
        result.scenes.forEach { assertEquals(CanvasSize.DEFAULT_LANDSCAPE, it.canvas) }
        assertEquals(project.activeSceneId, result.activeSceneId)
        assertEquals(project.sources, result.sources)
        assertEquals(project.assets, result.assets)
    }

    @Test
    fun alreadyCanonicalScenesArePreserved() {
        val scene = Scene(
            id = "scene",
            projectId = "project",
            name = "Landscape",
            canvas = CanvasSize.DEFAULT_LANDSCAPE
        )
        val result = SceneProjectOrientationConverter.convert(
            projectWith(scene),
            CanvasOrientation.LANDSCAPE
        )

        assertNotSame(scene, result.scenes.single())
        assertEquals(scene, result.scenes.single())
    }

    private fun projectWith(vararg scenes: Scene): SceneProject {
        val source = SceneSource(
            id = "source",
            type = SceneSourceType.TEXT,
            displayName = "Text",
            text = "OSS"
        )
        return SceneProject(
            id = "project",
            name = "Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            activeSceneId = scenes.first().id,
            scenes = scenes.toList(),
            sources = listOf(source)
        )
    }
}
