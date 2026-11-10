package com.iamahsana.ossstudio.scene.persistence

import com.iamahsana.ossstudio.scene.model.CanvasSize
import com.iamahsana.ossstudio.scene.model.Scene
import com.iamahsana.ossstudio.scene.model.SceneProject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneProjectJsonCodecTest {
    @Test
    fun legacySchemaV1RemainsReadableAndIsWrittenAsV2() {
        val project = SceneProject(
            id = "legacy-project",
            name = "Legacy",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            activeSceneId = "scene-1",
            scenes = listOf(
                Scene(
                    id = "scene-1",
                    projectId = "legacy-project",
                    name = "Original scene",
                    canvas = CanvasSize.PORTRAIT_4_5
                )
            )
        )

        val currentDocument = SceneProjectJsonCodec.encode(project)
        val legacyDocument = JSONObject(currentDocument)
            .put("schemaVersion", 1)
            .toString()

        val decoded = SceneProjectJsonCodec.decode(legacyDocument)
        val rewritten = JSONObject(SceneProjectJsonCodec.encode(decoded))

        assertEquals(project.id, decoded.id)
        assertEquals(project.name, decoded.name)
        assertEquals(project.scenes, decoded.scenes)
        assertEquals(project.activeSceneId, decoded.activeSceneId)
        assertEquals(2, rewritten.getInt("schemaVersion"))
    }

    @Test
    fun unsupportedFutureSchemaIsRejectedWithoutGuessing() {
        val project = SceneProject(
            id = "future-project",
            name = "Future",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L
        )
        val futureDocument = JSONObject(SceneProjectJsonCodec.encode(project))
            .put("schemaVersion", 999)
            .toString()

        val error = runCatching { SceneProjectJsonCodec.decode(futureDocument) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("Unsupported project schema"))
    }
}
