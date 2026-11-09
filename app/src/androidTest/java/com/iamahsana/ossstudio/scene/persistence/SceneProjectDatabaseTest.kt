package com.iamahsana.ossstudio.scene.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iamahsana.ossstudio.scene.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

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

}
