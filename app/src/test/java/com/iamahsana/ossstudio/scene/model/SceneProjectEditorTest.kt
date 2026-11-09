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
}
