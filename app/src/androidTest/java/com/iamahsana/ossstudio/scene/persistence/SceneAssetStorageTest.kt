package com.iamahsana.ossstudio.scene.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iamahsana.ossstudio.scene.model.SceneAsset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SceneAssetStorageTest {
    @Test fun deletesOnlyFilesInsideAppPrivateStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val relativePath = "scene_assets/test-cleanup/asset.png"
        val file = File(context.filesDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText("test")
        val asset = SceneAsset(
            relativePath = relativePath,
            mimeType = "image/png",
            byteSize = file.length(),
            sha256 = "a".repeat(64)
        )
        assertTrue(SceneAssetStorage.deleteAsset(context, asset))
        assertFalse(file.exists())
        assertFalse(SceneAssetStorage.deleteAsset(context, asset))
    }
    @Test fun refusesToDeleteFilesOutsideManagedAssetDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unrelated = SceneAsset(
            relativePath = "shared_prefs/important.xml",
            mimeType = "application/xml",
            byteSize = 1L,
            sha256 = "a".repeat(64)
        )
        assertThrows(IllegalArgumentException::class.java) {
            SceneAssetStorage.deleteAsset(context, unrelated)
        }
    }

}
