package com.iamahsana.ossstudio.scene.persistence

import com.iamahsana.ossstudio.scene.model.SceneSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

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
    @Test fun validatesRealMediaSignaturesRatherThanTrustingMimeLabels() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val gif = "GIF89a".toByteArray()
        val webp = "RIFF0000WEBP".toByteArray()
        val mp4 = "0000ftypisom".toByteArray()
        assertSignature("image/jpeg", jpeg, true)
        assertSignature("image/png", png, true)
        assertSignature("image/gif", gif, true)
        assertSignature("image/webp", webp, true)
        assertSignature("video/mp4", mp4, true)
        assertSignature("image/png", jpeg, false)
        assertSignature("video/mp4", "not a video".toByteArray(), false)
        assertSignature("image/gif", "GIF8".toByteArray(), false)
        assertSignature("video/webm", byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), true)
        assertSignature("video/quicktime", "0000ftypqt  ".toByteArray(), true)
        assertSignature("video/3gpp", "0000ftyp3gp6".toByteArray(), true)
    }

    private fun assertSignature(mime: String, bytes: ByteArray, expected: Boolean) {
        val file = File.createTempFile("oss-media-signature", ".bin")
        try {
            file.writeBytes(bytes)
            if (expected) assertTrue(SceneAssetStorage.hasValidContentSignature(file, mime))
            else assertFalse(SceneAssetStorage.hasValidContentSignature(file, mime))
        } finally {
            file.delete()
        }
    }

}
