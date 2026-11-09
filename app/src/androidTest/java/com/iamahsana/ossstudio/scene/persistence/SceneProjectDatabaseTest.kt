package com.iamahsana.ossstudio.scene.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iamahsana.ossstudio.scene.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SceneProjectDatabaseTest {
    @Test fun saveLoadRecentDeleteAndRecoveryAreConsistent() {
        val db=SceneProjectDatabase(ApplicationProvider.getApplicationContext<Context>())
        val id="scene-db-test"
        try {
            val source=SceneSource(id="text",type=SceneSourceType.TEXT,displayName="Title",text="LIVE")
            val scene=Scene(id="main",projectId=id,name="Main",elements=listOf(SceneElement(id="title",sourceId="text",zIndex=0)))
            val project=SceneProject(id=id,name="Test",createdAtEpochMillis=1,updatedAtEpochMillis=2,
                activeSceneId="main",scenes=listOf(scene),sources=listOf(source))
            db.save(project)
            assertEquals(project,db.get(id))
            assertEquals("Test", db.getRecovery(id)?.name)
            val revised = project.copy(name = "Revised", updatedAtEpochMillis = 4L)
            db.save(revised)
            assertEquals(revised, db.get(id))
            assertEquals("Test", db.getRecovery(id)?.name)
            assertTrue(db.listRecent().any { it.id==id })
            db.saveRecovery(project.copy(name="Last known good"),3L)
            assertEquals("Last known good",db.getRecovery(id)?.name)
            assertTrue(db.delete(id))
            assertNull(db.get(id))
            assertNull(db.getRecovery(id))
        } finally { db.delete(id);db.close() }
    }
    @Test fun versionOneDatabaseMigratesRecoveryTableWithoutLosingProjects() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("oss_projects.db")
        val legacy=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("oss_projects.db"),null)
        try {
            legacy.execSQL("CREATE TABLE projects(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,updated_at INTEGER NOT NULL,document TEXT NOT NULL)")
            legacy.execSQL("CREATE INDEX index_projects_updated_at ON projects(updated_at DESC)")
            legacy.version=1
        } finally { legacy.close() }

        val db=SceneProjectDatabase(context)
        try {
            val project=SceneProject(id="migration-test",name="Migrated",createdAtEpochMillis=1,updatedAtEpochMillis=1)
            db.saveRecovery(project,2L)
            assertEquals(project,db.getRecovery(project.id))
            assertEquals(2,db.readableDatabase.version)
        } finally {
            db.close()
            context.deleteDatabase("oss_projects.db")
        }
    }

    @Test fun closeFlushesNewestDebouncedSnapshot() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val id="autosave-close-test"
        val db=SceneProjectDatabase(context)
        val repository=SceneProjectRepository(db)
        val saved=CountDownLatch(1)
        val autosave=SceneProjectAutosave(repository, debounceMillis=10_000L, onSaved={ saved.countDown() })
        val project=SceneProject(id=id,name="Before",createdAtEpochMillis=1,updatedAtEpochMillis=1)
        try {
            autosave.schedule(project)
            autosave.schedule(project.copy(name="Latest",updatedAtEpochMillis=2))
            autosave.close()
            assertTrue("close must flush the pending snapshot", saved.await(5,TimeUnit.SECONDS))
            val loaded=CountDownLatch(1)
            var result: SceneProject? = null
            repository.load(id) { outcome -> result=outcome.getOrNull();loaded.countDown() }
            assertTrue(loaded.await(5,TimeUnit.SECONDS))
            assertEquals("Latest",result?.name)
        } finally {
            autosave.close()
            db.delete(id)
            repository.close()
            db.close()
        }
    }

}
