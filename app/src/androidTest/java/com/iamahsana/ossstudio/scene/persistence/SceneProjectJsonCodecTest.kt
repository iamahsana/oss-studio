package com.iamahsana.ossstudio.scene.persistence

import android.content.ContentValues
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
        val p=SceneProject("project","Round trip",100,200,"main",listOf(scene),listOf(source),listOf(asset), liveOrientation = CanvasOrientation.LANDSCAPE)
        assertEquals(p,SceneProjectJsonCodec.decode(SceneProjectJsonCodec.encode(p)))
    }

    @Test fun readsLegacySchemaV1AndWritesCurrentSchemaV2() {
        val project=SceneProject(id="legacy",name="Legacy",createdAtEpochMillis=1,updatedAtEpochMillis=2)
        val legacyObject = JSONObject(SceneProjectJsonCodec.encode(project)).put("schemaVersion", 1)
        legacyObject.remove("liveOrientation")
        val legacy = legacyObject.toString()

        val decoded=SceneProjectJsonCodec.decode(legacy)
        val rewritten=JSONObject(SceneProjectJsonCodec.encode(decoded))

        assertEquals(project.id,decoded.id)
        assertEquals(project.name,decoded.name)
        assertEquals(4,rewritten.getInt("schemaVersion"))
    }

    @Test
    fun roundTripsExtensionSourceIdentityAndOpaqueConfiguration() {
        val source = SceneSource(
            id = "extension-source",
            type = SceneSourceType.EXTENSION,
            displayName = "Community Badge",
            settings = mapOf("layout" to "{\"anchor\":\"top-right\"}", "theme" to "silver"),
            extensionId = "com.community.badges",
            typeId = "com.community.badges.animated-badge"
        )
        val project = SceneProject(
            id = "extension-project",
            name = "Extension project",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            sources = listOf(source)
        )

        val encoded = JSONObject(SceneProjectJsonCodec.encode(project))
        val decoded = SceneProjectJsonCodec.decode(encoded.toString())

        assertEquals(4, encoded.getInt("schemaVersion"))
        assertEquals(project, decoded)
        assertEquals(source.extensionId, decoded.sources.single().extensionId)
        assertEquals(source.typeId, decoded.sources.single().typeId)
        assertEquals(source.settings, decoded.sources.single().settings)
    }

    @Test
    fun readsSchemaV3SourcesWithoutExtensionFields() {
        val project = SceneProject(id = "v2", name = "Schema v2", createdAtEpochMillis = 1, updatedAtEpochMillis = 2)
        val schemaV2Object = JSONObject(SceneProjectJsonCodec.encode(project)).put("schemaVersion", 3)
        schemaV2Object.remove("liveOrientation")
        val schemaV2 = schemaV2Object.toString()

        val decoded = SceneProjectJsonCodec.decode(schemaV2)

        assertEquals(project, decoded)
        assertEquals(null, decoded.sources.firstOrNull()?.extensionId)
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
    fun databaseGetMigratesLegacyDocumentAndPreservesOriginalRecoveryJson() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "legacy-migration-test-${System.nanoTime()}.db"
        val database = SceneProjectDatabase(context, databaseName)
        val legacy = """
            {
              "schemaVersion": 3,
              "id": "legacy-db-project",
              "name": "Legacy database project",
              "createdAt": 1,
              "updatedAt": 2,
              "activeSceneId": "main",
              "version": 1,
              "scenes": [{
                "id": "main",
                "projectId": "legacy-db-project",
                "name": "Main",
                "w": 1440,
                "h": 1080,
                "orientation": "LANDSCAPE",
                "background": -16777216,
                "elements": []
              }],
              "sources": [],
              "assets": []
            }
        """.trimIndent()
        try {
            val values = ContentValues().apply {
                put("id", "legacy-db-project")
                put("name", "Legacy database project")
                put("updated_at", 2L)
                put("document", legacy)
            }
            check(database.writableDatabase.insert("projects", null, values) != -1L)

            val migrated = database.get("legacy-db-project")!!

            assertEquals(CanvasOrientation.LANDSCAPE, migrated.liveOrientation)
            assertEquals(CanvasSize.DEFAULT_LANDSCAPE, migrated.scenes.single().canvas)

            val recoveryJson = database.readableDatabase.query(
                "project_recovery", arrayOf("document"), "project_id=?",
                arrayOf("legacy-db-project"), null, null, null, "1"
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals(legacy, recoveryJson)

            val storedSchema = database.readableDatabase.query(
                "projects", arrayOf("document"), "id=?",
                arrayOf("legacy-db-project"), null, null, null, "1"
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                JSONObject(cursor.getString(0)).getInt("schemaVersion")
            }
            assertEquals(4, storedSchema)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
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
