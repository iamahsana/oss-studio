package com.iamahsana.ossstudio.stream.android

import com.iamahsana.ossstudio.scene.model.CanvasSize
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneOutputResolutionTest {
    @Test fun portraitCanvasKeepsPortraitOutput() {
        assertEquals(SceneOutputResolution(720, 1280), sceneOutputResolution(CanvasSize.DEFAULT_PORTRAIT))
    }

    @Test fun landscapeCanvasKeepsLandscapeOutput() {
        assertEquals(SceneOutputResolution(1280, 720), sceneOutputResolution(CanvasSize.DEFAULT_LANDSCAPE))
    }

    @Test fun customCanvasPreservesAspectRatioAndUsesEvenDimensions() {
        val output = sceneOutputResolution(CanvasSize(1000, 1000, com.iamahsana.ossstudio.scene.model.CanvasOrientation.LANDSCAPE))
        assertEquals(SceneOutputResolution(1280, 1280), output)
        assertEquals(0, output.width % 2)
        assertEquals(0, output.height % 2)
    }
}
