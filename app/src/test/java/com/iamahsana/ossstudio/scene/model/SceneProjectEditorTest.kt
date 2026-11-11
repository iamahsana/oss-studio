package com.iamahsana.ossstudio.scene.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneProjectEditorTest {
    private fun project(): SceneProject {
        val sourceA = SceneSource(id = "a", type = SceneSourceType.TEXT, displayName = "Text", text = "Hello")
        val sourceB = SceneSource(id = "b", type = SceneSourceType.TEXT, displayName = "Text 2", text = "World")
        val scene = Scene(
            id = "scene",
            projectId = "project",
            name = "Main",
            elements = listOf(
                SceneElement(id = "first", sourceId = "a", zIndex = 0),
                SceneElement(id = "second", sourceId = "b", zIndex = 1)
            )
        )
        return SceneProject(
            id = "project",
            name = "Project",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 10L,
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(sourceA, sourceB)
        )
    }

    @Test
    fun addElementUsesNextLayerAndLeavesOriginalImmutable() {
        val original = project()
        val updated = SceneProjectEditor.addElement(original, "scene", "a", 20L)
        assertEquals(3, updated.scenes.single().elements.size)
        assertEquals(2, updated.scenes.single().elements.last().zIndex)
        assertEquals(2, original.scenes.single().elements.size)
    }

    @Test
    fun moveElementReordersAndNormalizesLayerIndices() {
        val updated = SceneProjectEditor.moveElement(project(), "scene", "second", 0, 20L)
        assertEquals(listOf("second", "first"), updated.scenes.single().elements.map { it.id })
        assertEquals(listOf(0, 1), updated.scenes.single().elements.map { it.zIndex })
    }

    @Test
    fun transformUpdateChangesOnlySelectedElement() {
        val updated = SceneProjectEditor.updateTransform(
            project(), "scene", "first", ElementTransform(x = 25f, y = 40f), 20L
        )
        assertEquals(25f, updated.scenes.single().elements.first().transform.x)
        assertEquals(0f, updated.scenes.single().elements.last().transform.x)
    }

    @Test
    fun preciseRotationIsPersistedWithoutChangingElementGeometry() {
        val original = project()
        val transform = original.scenes.single().elements.first().transform.copy(
            x = 125f,
            y = 240f,
            width = 640f,
            height = 360f,
            rotationDegrees = 37.5f
        )

        val updated = SceneProjectEditor.updateTransform(original, "scene", "first", transform, 20L)
        val result = updated.scenes.single().elements.first().transform

        assertEquals(37.5f, result.rotationDegrees)
        assertEquals(125f, result.x)
        assertEquals(240f, result.y)
        assertEquals(640f, result.width)
        assertEquals(360f, result.height)
        assertEquals(0f, original.scenes.single().elements.first().transform.rotationDegrees)
    }

    @Test
    fun lockedElementRotationCannotBeChanged() {
        val original = SceneProjectEditor.setElementLocked(project(), "scene", "first", true, 20L)
        val rotated = original.scenes.single().elements.first().transform.copy(rotationDegrees = 90f)

        val updated = SceneProjectEditor.updateTransform(original, "scene", "first", rotated, 30L)

        assertEquals(original, updated)
        assertEquals(0f, updated.scenes.single().elements.first().transform.rotationDegrees)
    }

    @Test
    fun lockedElementTransformCannotBeChanged() {
        val original = SceneProjectEditor.setElementLocked(project(), "scene", "first", true, 20L)
        val updated = SceneProjectEditor.updateTransform(
            original, "scene", "first", ElementTransform(x = 120f, y = 80f), 30L
        )
        assertEquals(original, updated)
    }

    @Test
    fun removingSourceAlsoRemovesItsElements() {
        val updated = SceneProjectEditor.removeSource(project(), "a", 20L)
        assertFalse(updated.sources.any { it.id == "a" })
        assertFalse(updated.scenes.single().elements.any { it.sourceId == "a" })
        assertTrue(updated.sources.any { it.id == "b" })
    }

    @Test
    fun removingActiveSceneSelectsAnotherSceneOrClearsActiveScene() {
        val original = project()
        val other = Scene(id = "other", projectId = "project", name = "Other")
        val withOther = SceneProjectEditor.addScene(original, other, 20L)
        val updated = SceneProjectEditor.removeScene(withOther, "scene", 30L)
        assertEquals("other", updated.activeSceneId)
        val empty = SceneProjectEditor.removeScene(updated, "other", 40L)
        assertEquals(null, empty.activeSceneId)
    }

    @Test
    fun mutationsNeverMoveUpdatedTimestampBackwards() {
        val updated = SceneProjectEditor.setActiveScene(project(), "scene", 1L)
        assertEquals(10L, updated.updatedAtEpochMillis)
    }

    @Test
    fun mutationRejectsUnknownSceneAndSourceReferences() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectEditor.addElement(project(), "missing", "a", 20L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectEditor.addElement(project(), "scene", "missing", 20L)
        }
    }
    @Test
    fun sourceUpdatePreservesIdentityAndChangesConfiguration() {
        val original = project()
        val source = original.sources.first().copy(text = "Updated")
        val updated = SceneProjectEditor.updateSource(original, source, 30L)
        assertEquals("Updated", updated.sources.first().text)
        assertEquals("Hello", original.sources.first().text)
    }

    @Test
    fun removeElementNormalizesLayersAndRejectsUnknownElement() {
        val updated = SceneProjectEditor.removeElement(project(), "scene", "first", 30L)
        assertEquals(listOf("second"), updated.scenes.single().elements.map { it.id })
        assertEquals(0, updated.scenes.single().elements.single().zIndex)
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectEditor.removeElement(project(), "scene", "missing", 30L)
        }
    }

    @Test
    fun elementCropCanBeUpdatedWithoutChangingOriginalProject() {
        val original = project()
        val crop = CropRect(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f)
        val updated = SceneProjectEditor.updateElementCrop(original, "scene", "first", crop, 30L)
        assertEquals(crop, updated.scenes.single().elements.first().crop)
        assertEquals(CropRect(), original.scenes.single().elements.first().crop)
        assertEquals(CropRect(), updated.scenes.single().elements.last().crop)
    }

    @Test
    fun lockedElementCropCannotBeChanged() {
        val original = SceneProjectEditor.setElementLocked(project(), "scene", "first", true, 20L)
        val crop = CropRect(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f)
        val updated = SceneProjectEditor.updateElementCrop(original, "scene", "first", crop, 30L)
        assertEquals(original, updated)
        assertEquals(CropRect(), updated.scenes.single().elements.first().crop)
    }

    @Test
    fun cropModelRejectsInvertedAndOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            CropRect(left = 0.8f, right = 0.2f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CropRect(top = -0.1f)
        }
    }

    @Test
    fun elementOpacityCanBeAdjustedWithoutChangingOtherElements() {
        val original = project()
        val updated = SceneProjectEditor.setElementOpacity(original, "scene", "first", 0.35f, 30L)
        assertEquals(0.35f, updated.scenes.single().elements.first().opacity)
        assertEquals(1f, updated.scenes.single().elements.last().opacity)
        assertEquals(1f, original.scenes.single().elements.first().opacity)
    }

    @Test
    fun lockedElementOpacityCannotBeChanged() {
        val original = SceneProjectEditor.setElementLocked(project(), "scene", "first", true, 20L)
        val updated = SceneProjectEditor.setElementOpacity(original, "scene", "first", 0.25f, 30L)
        assertEquals(original, updated)
    }

    @Test
    fun elementOpacityRejectsNonFiniteAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectEditor.setElementOpacity(project(), "scene", "first", Float.NaN, 30L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectEditor.setElementOpacity(project(), "scene", "first", 1.5f, 30L)
        }
    }

    @Test
    fun elementVisibilityCanBeChangedWithoutChangingOtherElements() {
        val updated = SceneProjectEditor.setElementVisibility(project(), "scene", "first", false, 30L)
        assertFalse(updated.scenes.single().elements.first().visible)
        assertTrue(updated.scenes.single().elements.last().visible)
    }

    @Test
    fun canvasPresetChangeUpdatesOnlyTargetScene() {
        val original = project()
        val updated = SceneProjectEditor.updateSceneCanvas(
            original, "scene", CanvasSize.DEFAULT_LANDSCAPE, 30L
        )
        assertEquals(CanvasSize.DEFAULT_LANDSCAPE, updated.scenes.single().canvas)
        assertEquals(CanvasSize.DEFAULT_PORTRAIT, original.scenes.single().canvas)
    }

    @Test
    fun elementLockStateChangesWithoutMutatingOriginal() {
        val original = project()
        val updated = SceneProjectEditor.setElementLocked(original, "scene", "first", true, 30L)
        assertTrue(updated.scenes.single().elements.first().locked)
        assertFalse(original.scenes.single().elements.first().locked)
    }

    @Test
    fun removingSourceRetainsSharedAssetUntilLastReferenceIsRemoved() {
        val asset = SceneAsset("asset", "media/shared.png", "image/png", 10L, "a".repeat(64))
        val first = SceneSource("first", SceneSourceType.IMAGE, "First", assetId = asset.id)
        val second = SceneSource("second", SceneSourceType.IMAGE, "Second", assetId = asset.id)
        val scene = Scene(
            id = "shared-scene",
            projectId = "shared-project",
            name = "Main",
            elements = listOf(
                SceneElement("first-element", "first", zIndex = 0),
                SceneElement("second-element", "second", zIndex = 1)
            )
        )
        val project = SceneProject(
            id = "shared-project",
            name = "Shared",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            scenes = listOf(scene),
            sources = listOf(first, second),
            assets = listOf(asset)
        )
        val afterFirst = SceneProjectEditor.removeSource(project, "first", 2L)
        assertEquals(listOf(asset), afterFirst.assets)
        val afterLast = SceneProjectEditor.removeSource(afterFirst, "second", 3L)
        assertTrue(afterLast.assets.isEmpty())
    }

    @Test
    fun groupingAssignsOneGroupIdToExactlyTheSelectedElements() {
        val original = project()
        val grouped = SceneProjectEditor.groupElements(
            original, "scene", setOf("first", "second"), 30L, groupId = "pair"
        )
        assertEquals(listOf("pair", "pair"), grouped.scenes.single().elements.map { it.groupId })
        assertEquals(listOf(null, null), original.scenes.single().elements.map { it.groupId })
    }

    @Test
    fun groupingRequiresTwoExistingUngroupedElements() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectEditor.groupElements(project(), "scene", setOf("first"), 30L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectEditor.groupElements(project(), "scene", setOf("first", "missing"), 30L)
        }
        val grouped = SceneProjectEditor.groupElements(
            project(), "scene", setOf("first", "second"), 30L, groupId = "pair"
        )
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectEditor.groupElements(grouped, "scene", setOf("first", "second"), 40L)
        }
    }

    @Test
    fun translatingGroupMovesAllUnlockedMembersAndKeepsCanvasBounds() {
        val original = project()
        val grouped = SceneProjectEditor.groupElements(
            original, "scene", setOf("first", "second"), 30L, groupId = "pair"
        )
        val moved = SceneProjectEditor.translateGroup(grouped, "scene", "pair", 45f, 30f, 40L)
        assertEquals(45f, moved.scenes.single().elements.first().transform.x)
        assertEquals(45f, moved.scenes.single().elements.last().transform.x)
        assertEquals(30f, moved.scenes.single().elements.first().transform.y)
        assertEquals(30f, moved.scenes.single().elements.last().transform.y)
        assertEquals(0f, grouped.scenes.single().elements.first().transform.x)
    }

    @Test
    fun groupingRejectsAGroupIdAlreadyUsedByAnotherGroup() {
        val original = project()
        val withThird = SceneProjectEditor.addElement(original, "scene", "a", 20L)
        val withFourth = SceneProjectEditor.addElement(withThird, "scene", "b", 30L)
        val grouped = SceneProjectEditor.groupElements(
            withFourth, "scene", setOf("first", "second"), 40L, groupId = "pair"
        )
        val remainingIds = grouped.scenes.single().elements
            .filter { it.id != "first" && it.id != "second" }
            .map { it.id }
            .toSet()
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectEditor.groupElements(grouped, "scene", remainingIds, 50L, groupId = "pair")
        }
    }

    @Test
    fun translatingGroupSafelyNoOpsWhenAnElementCannotFitCanvas() {
        val original = project()
        val oversizedFirst = SceneProjectEditor.updateTransform(
            original, "scene", "first", ElementTransform(width = 2_000f, height = 100f), 20L
        )
        val oversizedBoth = SceneProjectEditor.updateTransform(
            oversizedFirst, "scene", "second", ElementTransform(width = 2_000f, height = 100f), 30L
        )
        val grouped = SceneProjectEditor.groupElements(
            oversizedBoth, "scene", setOf("first", "second"), 40L, groupId = "oversized"
        )
        val moved = SceneProjectEditor.translateGroup(grouped, "scene", "oversized", 20f, 20f, 50L)
        assertEquals(grouped, moved)
    }

    @Test
    fun scalingGroupResizesMembersTogetherAndPreservesTheirRelativeLayout() {
        val grouped = SceneProjectEditor.groupElements(
            project(), "scene", setOf("first", "second"), 30L, groupId = "pair"
        )
        val scaled = SceneProjectEditor.scaleGroup(grouped, "scene", "pair", 1.5f, 40L)
        val elements = scaled.scenes.single().elements
        val originalElements = grouped.scenes.single().elements
        originalElements.zip(elements).forEach { (before, after) ->
            assertEquals(before.transform.width * 1.5f, after.transform.width, 0.001f)
            assertEquals(before.transform.height * 1.5f, after.transform.height, 0.001f)
        }
        assertEquals(originalElements.first().transform.x, elements.first().transform.x, 0.001f)
        assertEquals(originalElements.first().transform.y, elements.first().transform.y, 0.001f)
        assertEquals(0f, originalElements.first().transform.x)
    }

    @Test
    fun scalingGroupKeepsMembersInsideCanvasAndDoesNotResizeLockedMembers() {
        val original = project()
        val moved = SceneProjectEditor.updateTransform(
            original, "scene", "second", ElementTransform(x = 900f, y = 900f, width = 80f, height = 80f), 20L
        )
        val locked = SceneProjectEditor.setElementLocked(moved, "scene", "second", true, 30L)
        val grouped = SceneProjectEditor.groupElements(locked, "scene", setOf("first", "second"), 40L, groupId = "pair")
        val scaled = SceneProjectEditor.scaleGroup(grouped, "scene", "pair", 2f, 50L)
        val first = scaled.scenes.single().elements.first()
        val second = scaled.scenes.single().elements.last()
        assertTrue(first.transform.x + first.transform.width <= 1080f)
        assertTrue(first.transform.y + first.transform.height <= 1920f)
        assertEquals(grouped.scenes.single().elements.last().transform, second.transform)
    }

    @Test
    fun ungroupingClearsOnlyTheRequestedGroup() {
        val original = project()
        val grouped = SceneProjectEditor.groupElements(
            original, "scene", setOf("first", "second"), 30L, groupId = "pair"
        )
        val ungrouped = SceneProjectEditor.ungroupElements(grouped, "scene", "pair", 40L)
        assertEquals(listOf(null, null), ungrouped.scenes.single().elements.map { it.groupId })
        assertEquals(listOf("pair", "pair"), grouped.scenes.single().elements.map { it.groupId })
    }

    @Test
    fun duplicateElementCreatesIndependentTopLayerCopy() {
        val original = project()
        val updated = SceneProjectEditor.duplicateElement(original, "scene", "first", 30L)
        val elements = updated.scenes.single().elements
        assertEquals(3, elements.size)
        assertNotEquals("first", elements.last().id)
        assertEquals("a", elements.last().sourceId)
        assertEquals(2, elements.last().zIndex)
        assertFalse(elements.last().locked)
        assertEquals(2, original.scenes.single().elements.size)
    }


    @Test
    fun canvasSupportsCustomPortraitLandscapeAndSquareDimensions() {
        assertEquals(
            CanvasSize(720, 1280, CanvasOrientation.PORTRAIT),
            CanvasSize(720, 1280, CanvasOrientation.PORTRAIT)
        )
        assertEquals(
            CanvasSize(2560, 1440, CanvasOrientation.LANDSCAPE),
            CanvasSize(2560, 1440, CanvasOrientation.LANDSCAPE)
        )
        assertEquals(
            CanvasSize(1080, 1080, CanvasOrientation.PORTRAIT),
            CanvasSize.SQUARE
        )
    }

    @Test
    fun canvasRejectsDimensionsOutsideSupportedRangeAndMismatchedOrientation() {
        assertThrows(IllegalArgumentException::class.java) {
            CanvasSize(63, 720, CanvasOrientation.PORTRAIT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanvasSize(4097, 1080, CanvasOrientation.LANDSCAPE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanvasSize(720, 1280, CanvasOrientation.LANDSCAPE)
        }
    }

    @Test
    fun customCanvasDimensionsRemainOnTheSceneAfterProjectMutation() {
        val original = project()
        val custom = CanvasSize(720, 1280, CanvasOrientation.PORTRAIT)
        val updated = SceneProjectEditor.updateSceneCanvas(original, "scene", custom, 30L)
        assertEquals(custom, updated.scenes.single().canvas)
        assertEquals(CanvasSize.DEFAULT_PORTRAIT, original.scenes.single().canvas)
    }

}
