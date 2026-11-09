package com.iamahsana.ossstudio.scene.persistence

import com.iamahsana.ossstudio.scene.model.SceneSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneAssetStorageTest {
    @Test fun classifiesOnlySupportedImageAndVideoFormats() {
        assertEquals("jpg" to SceneSourceType.IMAGE, SceneAssetStorage.classifyMimeType("image/jpeg"))
        assertEquals("gif" to SceneSourceType.GIF, SceneAssetStorage.classifyMimeType("image/gif"))
        assertEquals("mp4" to SceneSourceType.VIDEO, SceneAssetStorage.classifyMimeType("video/mp4"))
        assertEquals("webm" to SceneSourceType.VIDEO, SceneAssetStorage.classifyMimeType("video/webm"))
        assertEquals("png" to SceneSourceType.IMAGE, SceneAssetStorage.classifyMimeType("IMAGE/PNG; charset=binary"))
    }

    @Test fun rejectsUnsupportedAndPotentiallyActiveFormats() {
        assertNull(SceneAssetStorage.classifyMimeType("image/svg+xml"))
        assertNull(SceneAssetStorage.classifyMimeType("application/octet-stream"))
        assertNull(SceneAssetStorage.classifyMimeType("text/html"))
    }
}
