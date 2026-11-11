package com.iamahsana.ossstudio.scene.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iamahsana.ossstudio.scene.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

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
    @Test fun rejectsUnsupportedDocumentSchemaBeforeDecoding() {
        val project=SceneProject(id="schema",name="Schema",createdAtEpochMillis=1,updatedAtEpochMillis=1)
        val encoded=SceneProjectJsonCodec.encode(project).replace("\"schemaVersion\":2","\"schemaVersion\":99")
        assertThrows(IllegalArgumentException::class.java) {
            SceneProjectJsonCodec.decode(encoded)
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

}
