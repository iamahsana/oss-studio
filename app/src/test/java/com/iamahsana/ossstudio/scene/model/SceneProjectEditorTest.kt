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

}
