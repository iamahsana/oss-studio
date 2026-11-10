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
    fun projectValidatesSceneSourceAndAssetOwnership() {
        val projectId = "project"
        val asset = SceneAsset(
            id = "asset",
            relativePath = "media/photo.png",
            mimeType = "image/png",
            byteSize = 12L,
            sha256 = "a".repeat(64)
        )
        val source = SceneSource(
            id = "source",
            type = SceneSourceType.IMAGE,
            displayName = "Photo",
            assetId = asset.id
        )
        val scene = Scene(
            id = "scene",
            projectId = projectId,
            name = "Main",
            elements = listOf(SceneElement(sourceId = source.id, zIndex = 0))
        )
        val project = SceneProject(
            id = projectId,
            name = "Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(source),
            assets = listOf(asset)
        )
        assertEquals(scene.id, project.activeSceneId)

        assertThrows(IllegalArgumentException::class.java) {
            project.copy(sources = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            project.copy(assets = emptyList())
        }
    }

    @Test
    fun sceneCannotBelongToAnotherProject() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneProject(
                id = "project",
                name = "Project",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                scenes = listOf(Scene(projectId = "other-project", name = "Scene"))
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
    fun extensionSourcesRequireStableNamespacedIdentifiers() {
        val source = SceneSource(
            type = SceneSourceType.EXTENSION,
            displayName = "Community Badge",
            extensionId = "com.community.badges",
            typeId = "com.community.badges.animated-badge",
            settings = mapOf("preset" to "silver")
        )
        assertEquals("com.community.badges", source.extensionId)
        assertEquals("com.community.badges.animated-badge", source.typeId)

        assertThrows(IllegalArgumentException::class.java) {
            SceneSource(
                type = SceneSourceType.EXTENSION,
                displayName = "Missing identity",
                extensionId = "com.community.badges"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneSource(
                type = SceneSourceType.EXTENSION,
                displayName = "Invalid identity",
                extensionId = "../bad",
                typeId = "community.badge"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneSource(
                type = SceneSourceType.TEXT,
                displayName = "Built-in with extension identity",
                extensionId = "com.community.badges",
                typeId = "com.community.badges.badge"
            )
        }
    }

    @Test
    fun assetPathsRejectTraversalAndPlatformSpecificAbsolutePaths() {
        val invalidPaths = listOf("../outside.png", "media/../outside.png", "/absolute.png", "C:/outside.png", "media\\photo.png", "media//photo.png", "media/./photo.png")
        invalidPaths.forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                SceneAsset(relativePath = path, mimeType = "image/png", byteSize = 1L, sha256 = "a".repeat(64))
            }
        }
    }

    @Test
    fun assetPathsAllowOrdinaryNestedAndDottedFilenames() {
        val asset = SceneAsset(relativePath = "media/v1.2/photo.final.png", mimeType = "image/png", byteSize = 1L, sha256 = "a".repeat(64))
        assertEquals("media/v1.2/photo.final.png", asset.relativePath)
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
