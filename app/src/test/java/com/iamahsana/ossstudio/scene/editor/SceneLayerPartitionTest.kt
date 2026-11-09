package com.iamahsana.ossstudio.scene.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLayerPartitionTest {
    @Test fun allLayerIncludesEveryElement() {
        assertTrue(isSceneElementInLayer(-10, SceneCanvasLayerController.LayerPosition.ALL, 5))
        assertTrue(isSceneElementInLayer(10, SceneCanvasLayerController.LayerPosition.ALL, 5))
    }

    @Test fun belowAndAboveLayersExcludeShareScreenZIndex() {
        val shareZ = 4
        assertTrue(isSceneElementInLayer(3, SceneCanvasLayerController.LayerPosition.BELOW_SHARE_SCREEN, shareZ))
        assertFalse(isSceneElementInLayer(4, SceneCanvasLayerController.LayerPosition.BELOW_SHARE_SCREEN, shareZ))
        assertFalse(isSceneElementInLayer(5, SceneCanvasLayerController.LayerPosition.BELOW_SHARE_SCREEN, shareZ))
        assertFalse(isSceneElementInLayer(3, SceneCanvasLayerController.LayerPosition.ABOVE_SHARE_SCREEN, shareZ))
        assertFalse(isSceneElementInLayer(4, SceneCanvasLayerController.LayerPosition.ABOVE_SHARE_SCREEN, shareZ))
        assertTrue(isSceneElementInLayer(5, SceneCanvasLayerController.LayerPosition.ABOVE_SHARE_SCREEN, shareZ))
    }

    @Test fun splitLayersRejectMissingShareScreenIndex() {
        assertFalse(isSceneElementInLayer(0, SceneCanvasLayerController.LayerPosition.BELOW_SHARE_SCREEN, null))
        assertFalse(isSceneElementInLayer(0, SceneCanvasLayerController.LayerPosition.ABOVE_SHARE_SCREEN, null))
    }
}
