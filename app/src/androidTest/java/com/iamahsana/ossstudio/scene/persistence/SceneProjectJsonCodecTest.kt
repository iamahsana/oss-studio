package com.iamahsana.ossstudio.scene.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iamahsana.ossstudio.scene.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class SceneProjectJsonCodecTest {
    @Test fun roundTripsSceneGeometrySourcesAndAssetMetadata() {
        val asset=SceneAsset("asset","media/photo.png","image/png",12,"a".repeat(64),"CC0","created")
        val source=SceneSource("image",SceneSourceType.IMAGE,"Photo",assetId=asset.id,settings=mapOf("fit" to "cover"))
        val scene=Scene("main","project","Main",CanvasSize.DEFAULT_LANDSCAPE,0xFF112233,
            listOf(SceneElement("element","image",ElementTransform(12f,24f,640f,360f,15f),CropRect(.1f,.2f,.9f,.8f),.75f,true,true,4,"group")))
        val p=SceneProject("project","Round trip",100,200,"main",listOf(scene),listOf(source),listOf(asset))
        assertEquals(p,SceneProjectJsonCodec.decode(SceneProjectJsonCodec.encode(p)))
    }

    @Test
    fun roundTripsExtensionRequirementsOnSourcesAndElements() {
        val requirement = ExtensionRequirement("com.example.text", "2.1.0", "element.text")
        val source = SceneSource(
            id = "source",
            type = SceneSourceType.TEXT,
            displayName = "Community text",
            text = "Hello",
            extensionRequirement = requirement
        )
        val scene = Scene(
            id = "main",
            projectId = "extension-project",
            name = "Main",
            elements = listOf(
                SceneElement("element", source.id, zIndex = 0, extensionRequirement = requirement)
            )
        )
        val project = SceneProject(
            id = "extension-project",
            name = "Extension project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(source)
        )
        assertEquals(project, SceneProjectJsonCodec.decode(SceneProjectJsonCodec.encode(project)))
    }

    @Test
    fun readsSchemaV2WithoutExtensionMetadata() {
        val source = SceneSource("source", SceneSourceType.TEXT, "Text", text = "Hello")
        val scene = Scene(
            id = "main",
            projectId = "schema-v2",
            name = "Main",
            elements = listOf(SceneElement("element", source.id, zIndex = 0))
        )
        val project = SceneProject(
            id = "schema-v2",
            name = "Schema v2",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            activeSceneId = scene.id,
            scenes = listOf(scene),
            sources = listOf(source)
        )
        val legacy = JSONObject(SceneProjectJsonCodec.encode(project)).put("schemaVersion", 2)
        legacy.getJSONArray("sources").getJSONObject(0).remove("extension")
        legacy.getJSONArray("scenes").getJSONObject(0).getJSONArray("elements").getJSONObject(0).remove("extension")
        assertEquals(project, SceneProjectJsonCodec.decode(legacy.toString()))
    }

    @Test fun readsLegacySchemaV1AndWritesCurrentSchemaV3() {
        val project=SceneProject(id="legacy",name="Legacy",createdAtEpochMillis=1,updatedAtEpochMillis=2)
        val legacy=JSONObject(SceneProjectJsonCodec.encode(project))
            .put("schemaVersion",1)
            .toString()

        val decoded=SceneProjectJsonCodec.decode(legacy)
        val rewritten=JSONObject(SceneProjectJsonCodec.encode(decoded))

        assertEquals(project.id,decoded.id)
        assertEquals(project.name,decoded.name)
        assertEquals(3,rewritten.getInt("schemaVersion"))
    }

    @Test fun rejectsUnsupportedFutureDocumentSchemaBeforeDecoding() {
        val project=SceneProject(id="schema",name="Schema",createdAtEpochMillis=1,updatedAtEpochMillis=1)
        val future=JSONObject(SceneProjectJsonCodec.encode(project))
            .put("schemaVersion",99)
            .toString()
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectJsonCodec.decode(future)
        }
    }

    @Test fun rejectsBrokenElementSourceReferences() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneProject(
                id = "refs",
                name = "References",
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
                scenes = listOf(
                    Scene(
                        id = "main",
                        projectId = "refs",
                        name = "Main",
                        elements = listOf(SceneElement(id = "element", sourceId = "missing", zIndex = 0))
                    )
                )
            )
        }
    }

    @Test
    fun databaseOrientationConversionUpdatesEverySceneAndKeepsRecoverySnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "orientation-test-${System.nanoTime()}.db"
        val database = SceneProjectDatabase(context, databaseName)
        try {
            val projectId = "orientation-project"
            val original = SceneProject(
                id = projectId,
                name = "Orientation test",
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 2,
                activeSceneId = "portrait",
                scenes = listOf(
                    Scene(id = "portrait", projectId = projectId, name = "Portrait"),
                    Scene(
                        id = "custom",
                        projectId = projectId,
                        name = "Custom landscape",
                        canvas = CanvasSize.LANDSCAPE_4_3
                    )
                )
            )
            database.save(original)

            val converted = database.convertProjectOrientation(projectId, CanvasOrientation.LANDSCAPE)

            assertEquals(
                listOf(CanvasSize.DEFAULT_LANDSCAPE, CanvasSize.DEFAULT_LANDSCAPE),
                converted.scenes.map { it.canvas }
            )
            assertEquals(converted, database.get(projectId))
            assertEquals(original, database.getRecovery(projectId))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

}
