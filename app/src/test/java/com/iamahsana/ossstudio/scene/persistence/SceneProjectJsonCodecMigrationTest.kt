package com.iamahsana.ossstudio.scene.persistence

import com.iamahsana.ossstudio.scene.model.CanvasOrientation
import com.iamahsana.ossstudio.scene.model.CanvasSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneProjectJsonCodecMigrationTest {
    @Test
    fun schemaV1MigratesAllScenesToActiveSceneLiveOrientation() {
        val decoded = SceneProjectJsonCodec.decodeWithMetadata(legacyPortraitProject())

        assertTrue(decoded.migrated)
        assertEquals(1, decoded.sourceSchemaVersion)
        assertEquals(CanvasOrientation.PORTRAIT, decoded.project.liveOrientation)
        assertEquals(2, decoded.project.scenes.size)
        assertTrue(decoded.project.scenes.all { it.canvas == CanvasSize.DEFAULT_PORTRAIT })

        val portraitElement = decoded.project.scenes.first { it.id == "portrait" }.elements.single()
        assertEquals(100f, portraitElement.transform.x, 0.001f)
        assertEquals(385f, portraitElement.transform.y, 0.001f)
        assertEquals(240f, portraitElement.transform.width, 0.001f)
        assertEquals(120f, portraitElement.transform.height, 0.001f)
        assertEquals(17f, portraitElement.transform.rotationDegrees, 0.001f)
        assertEquals(0.1f, portraitElement.crop.left, 0.001f)
        assertEquals(0.8f, portraitElement.crop.bottom, 0.001f)
        assertEquals(0.65f, portraitElement.opacity, 0.001f)
        assertFalse(portraitElement.visible)
        assertTrue(portraitElement.locked)
        assertEquals(3, portraitElement.zIndex)
        assertEquals("group-a", portraitElement.groupId)

        assertEquals("Hello OSS", decoded.project.sources.single().text)
        assertEquals("project", decoded.project.id)
        assertEquals("portrait", decoded.project.activeSceneId)
    }

    @Test
    fun migratedProjectEncodesAsSchemaV2AndDoesNotMigrateAgain() {
        val migrated = SceneProjectJsonCodec.decode(legacyPortraitProject())
        val encoded = SceneProjectJsonCodec.encode(migrated)
        val decodedAgain = SceneProjectJsonCodec.decodeWithMetadata(encoded)

        assertTrue(encoded.contains("\"schemaVersion\":2"))
        assertTrue(encoded.contains("\"liveOrientation\":\"PORTRAIT\""))
        assertFalse(decodedAgain.migrated)
        assertEquals(2, decodedAgain.sourceSchemaVersion)
        assertEquals(migrated, decodedAgain.project)
    }

    @Test
    fun unsupportedFutureSchemaIsRejected() {
        val future = legacyPortraitProject().replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        val failure = runCatching { SceneProjectJsonCodec.decode(future) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun legacyPortraitProject(): String = """
        {
          "schemaVersion": 1,
          "id": "project",
          "name": "Legacy project",
          "createdAt": 1,
          "updatedAt": 2,
          "activeSceneId": "portrait",
          "version": 1,
          "scenes": [
            {
              "id": "portrait",
              "projectId": "project",
              "name": "Portrait",
              "w": 1080,
              "h": 1350,
              "orientation": "PORTRAIT",
              "background": -16777216,
              "elements": [
                {
                  "id": "element-1",
                  "source": "text-source",
                  "x": 100,
                  "y": 100,
                  "w": 240,
                  "h": 120,
                  "rotation": 17,
                  "cl": 0.1,
                  "ct": 0.2,
                  "cr": 0.9,
                  "cb": 0.8,
                  "opacity": 0.65,
                  "visible": false,
                  "locked": true,
                  "z": 3,
                  "group": "group-a"
                }
              ]
            },
            {
              "id": "square",
              "projectId": "project",
              "name": "Square legacy scene",
              "w": 1080,
              "h": 1080,
              "orientation": "PORTRAIT",
              "background": -16777216,
              "elements": []
            }
          ],
          "sources": [
            {
              "id": "text-source",
              "type": "TEXT",
              "name": "Title",
              "enabled": true,
              "asset": null,
              "text": "Hello OSS",
              "settings": {}
            }
          ],
          "assets": []
        }
    """.trimIndent()
}
