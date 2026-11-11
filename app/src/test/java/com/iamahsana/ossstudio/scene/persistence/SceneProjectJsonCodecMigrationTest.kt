package com.iamahsana.ossstudio.scene.persistence

import com.iamahsana.ossstudio.scene.model.CanvasOrientation
import com.iamahsana.ossstudio.scene.model.CanvasSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SceneProjectJsonCodecMigrationTest {
    @Test
    fun schemaV1MigratesEverySceneToActiveSceneOrientationAndPreservesElementProperties() {
        val decoded = SceneProjectJsonCodec.decodeWithMetadata(legacyV1())

        assertTrue(decoded.migrated)
        assertEquals(1, decoded.sourceSchemaVersion)
        assertEquals(CanvasOrientation.PORTRAIT, decoded.project.liveOrientation)
        assertTrue(decoded.project.scenes.all { it.canvas == CanvasSize.DEFAULT_PORTRAIT })

        val element = decoded.project.scenes.first { it.id == "portrait" }.elements.single()
        assertEquals(100f, element.transform.x, 0.001f)
        assertEquals(385f, element.transform.y, 0.001f)
        assertEquals(240f, element.transform.width, 0.001f)
        assertEquals(120f, element.transform.height, 0.001f)
        assertEquals(17f, element.transform.rotationDegrees, 0.001f)
        assertEquals(0.1f, element.crop.left, 0.001f)
        assertEquals(0.8f, element.crop.bottom, 0.001f)
        assertEquals(0.65f, element.opacity, 0.001f)
        assertFalse(element.visible)
        assertTrue(element.locked)
        assertEquals(3, element.zIndex)
        assertEquals("group-a", element.groupId)
        assertEquals("Hello OSS", decoded.project.sources.single().text)
    }

    @Test
    fun schemaV3MigrationKeepsExtensionIdentityAndWritesSchemaV4() {
        val v3 = JSONObject(legacyV1())
            .put("schemaVersion", 3)
            .put("activeSceneId", "landscape")
            .put("scenes", JSONObject(legacyV1()).getJSONArray("scenes"))
            .put("sources", JSONObject(legacyV1()).getJSONArray("sources").put(JSONObject()
                .put("id", "badge-source")
                .put("type", "EXTENSION")
                .put("name", "Community Badge")
                .put("enabled", true)
                .put("asset", JSONObject.NULL)
                .put("text", JSONObject.NULL)
                .put("settings", JSONObject().put("theme", "silver"))
                .put("extensionId", "com.community.badges")
                .put("typeId", "com.community.badges.animated-badge")))
            .toString()
        val decoded = SceneProjectJsonCodec.decodeWithMetadata(v3)

        assertTrue(decoded.migrated)
        assertEquals(3, decoded.sourceSchemaVersion)
        assertEquals(CanvasOrientation.LANDSCAPE, decoded.project.liveOrientation)
        assertTrue(decoded.project.scenes.all { it.canvas == CanvasSize.DEFAULT_LANDSCAPE })
        assertEquals("com.community.badges", decoded.project.sources.single().extensionId)
        assertEquals("com.community.badges.animated-badge", decoded.project.sources.single().typeId)

        val encoded = SceneProjectJsonCodec.encode(decoded.project)
        val decodedAgain = SceneProjectJsonCodec.decodeWithMetadata(encoded)
        assertEquals(4, JSONObject(encoded).getInt("schemaVersion"))
        assertFalse(decodedAgain.migrated)
        assertEquals(decoded.project, decodedAgain.project)
    }

    @Test
    fun schemaV2WithPersistedOrientationRemainsReadable() {
        val v2 = JSONObject(legacyV1())
            .put("schemaVersion", 2)
            .put("liveOrientation", "LANDSCAPE")
            .put("activeSceneId", "portrait")
            .toString()

        val decoded = SceneProjectJsonCodec.decodeWithMetadata(v2)

        assertTrue(decoded.migrated)
        assertEquals(CanvasOrientation.LANDSCAPE, decoded.project.liveOrientation)
        assertTrue(decoded.project.scenes.all { it.canvas == CanvasSize.DEFAULT_LANDSCAPE })
    }

    @Test
    fun rejectsUnsupportedFutureSchema() {
        val future = JSONObject(legacyV1()).put("schemaVersion", 99).toString()
        val failure = runCatching { SceneProjectJsonCodec.decode(future) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun legacyV1(): String = """
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
              "elements": [{
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
              }]
            },
            {
              "id": "square",
              "projectId": "project",
              "name": "Square",
              "w": 1080,
              "h": 1080,
              "orientation": "PORTRAIT",
              "background": -16777216,
              "elements": []
            },
            {
              "id": "landscape",
              "projectId": "project",
              "name": "Landscape",
              "w": 1440,
              "h": 1080,
              "orientation": "LANDSCAPE",
              "background": -16777216,
              "elements": []
            }
          ],
          "sources": [{
            "id": "text-source",
            "type": "TEXT",
            "name": "Title",
            "enabled": true,
            "asset": null,
            "text": "Hello OSS",
            "settings": {}
          }],
          "assets": []
        }
    """.trimIndent()
}
