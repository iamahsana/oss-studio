package com.iamahsana.ossstudio.scene.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneProjectFactoryTest {
    @Test fun createsAnEmptyPortraitProjectWithOneActiveScene() {
        val project = SceneProjectFactory.create("  First stream  ", 123L, projectId = "p", sceneId = "s")
        assertEquals("First stream", project.name)
        assertEquals("p", project.id)
        assertEquals("s", project.activeSceneId)
        assertEquals(1, project.scenes.size)
        assertTrue(project.scenes.single().elements.isEmpty())
        assertEquals(CanvasSize.DEFAULT_PORTRAIT, project.scenes.single().canvas)
    }

    @Test fun rejectsBlankNamesAndNegativeTimestamps() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SceneProjectFactory.create("  ", 1L)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SceneProjectFactory.create("Valid", -1L)
        }
    }
}
