package com.iamahsana.ossstudio.scene.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iamahsana.ossstudio.scene.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneProjectDatabaseTest {
    @Test fun saveLoadRecentAndDeleteAreConsistent() {
        val db=SceneProjectDatabase(ApplicationProvider.getApplicationContext<Context>())
        val id="scene-db-test"
        try {
            val source=SceneSource(id="text",type=SceneSourceType.TEXT,displayName="Title",text="LIVE")
            val scene=Scene(id="main",projectId=id,name="Main",elements=listOf(SceneElement(id="title",sourceId="text",zIndex=0)))
            val project=SceneProject(id=id,name="Test",createdAtEpochMillis=1,updatedAtEpochMillis=2,
                activeSceneId="main",scenes=listOf(scene),sources=listOf(source))
            db.save(project)
            assertEquals(project,db.get(id))
            assertTrue(db.listRecent().any { it.id==id })
            assertTrue(db.delete(id))
            assertNull(db.get(id))
        } finally { db.delete(id);db.close() }
    }
}
