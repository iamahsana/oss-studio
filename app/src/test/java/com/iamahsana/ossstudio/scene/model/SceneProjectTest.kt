package com.iamahsana.ossstudio.scene.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SceneProjectTest {
    @Test
    fun defaultCanvasSizesUseLogicalPortraitAndLandscapeDimensions() {
        assertEquals(1080, CanvasSize.DEFAULT_PORTRAIT.width)
        assertEquals(1920, CanvasSize.DEFAULT_PORTRAIT.height)
        assertEquals(1920, CanvasSize.DEFAULT_LANDSCAPE.width)
        assertEquals(1080, CanvasSize.DEFAULT_LANDSCAPE.height)
    }

    @Test
    fun canvasRejectsDimensionsThatConflictWithOrientation() {
        assertThrows(IllegalArgumentException::class.java) {
            CanvasSize(1080, 1920, CanvasOrientation.LANDSCAPE)
        }
    }

    @Test
    fun projectRequiresActiveSceneToBelongToProject() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneProject(
                name = "Project",
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 10L,
                activeSceneId = "missing"
            )
        }
    }

    @Test
    fun sceneRequiresUniqueElementIdsAndZIndices() {
        val first = SceneElement(sourceId = "source-a", zIndex = 0)
        val duplicateId = first.copy(zIndex = 1)
        assertThrows(IllegalArgumentException::class.java) {
            Scene(
                projectId = "project",
                name = "Scene",
                elements = listOf(first, duplicateId)
            )
        }

        val second = SceneElement(sourceId = "source-b", zIndex = 0)
        assertThrows(IllegalArgumentException::class.java) {
            Scene(
                projectId = "project",
                name = "Scene",
                elements = listOf(first, second)
            )
        }
    }

    @Test
    fun transformRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            ElementTransform(width = 0f)
        }
    }

    @Test
    fun cropMustHavePositiveArea() {
        assertThrows(IllegalArgumentException::class.java) {
            CropRect(left = 0.8f, right = 0.2f)
        }
    }

    @Test
    fun textIsOnlyAllowedOnTextSources() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneSource(
                type = SceneSourceType.IMAGE,
                displayName = "Image",
                text = "not an image configuration"
            )
        }
    }

    @Test
    fun assetPathAndChecksumAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneAsset(
                relativePath = "../outside.png",
                mimeType = "image/png",
                byteSize = 1L,
                sha256 = "a".repeat(64)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneAsset(
                relativePath = "assets/image.png",
                mimeType = "image/png",
                byteSize = 1L,
                sha256 = "invalid"
            )
        }
    }
}
