package com.iamahsana.ossstudio.scene.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SceneEditorGuidesTest {
    @Test
    fun snapUsesLogicalCanvasGridAndRoundsToNearestStep() {
        assertEquals(24f, snapSceneCoordinate(22f), 0f)
        assertEquals(48f, snapSceneCoordinate(37f), 0f)
        assertEquals(0f, snapSceneCoordinate(10f), 0f)
    }

    @Test
    fun snapRejectsInvalidCoordinatesAndGridSizes() {
        assertThrows(IllegalArgumentException::class.java) { snapSceneCoordinate(-1f) }
        assertThrows(IllegalArgumentException::class.java) { snapSceneCoordinate(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { snapSceneCoordinate(12f, 0f) }
    }

    @Test
    fun clampKeepsElementInsideCanvasBounds() {
        assertEquals(0f, clampSceneCoordinate(-10f, 1080f, 320f), 0f)
        assertEquals(760f, clampSceneCoordinate(900f, 1080f, 320f), 0f)
        assertEquals(0f, clampSceneCoordinate(10f, 200f, 320f), 0f)
    }
}
