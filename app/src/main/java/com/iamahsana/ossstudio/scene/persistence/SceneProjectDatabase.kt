package com.iamahsana.ossstudio.scene.persistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.iamahsana.ossstudio.scene.model.CanvasOrientation
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.scene.model.SceneProjectOrientationConverter

class SceneProjectDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {
    companion object {
        private const val DATABASE_NAME = "oss_projects.db"
        private const val DATABASE_VERSION = 2
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projects(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,updated_at INTEGER NOT NULL,document TEXT NOT NULL)")
        db.execSQL("CREATE INDEX index_projects_updated_at ON projects(updated_at DESC)")
        db.execSQL("CREATE TABLE project_recovery(project_id TEXT PRIMARY KEY NOT NULL, saved_at INTEGER NOT NULL, document TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE project_recovery(project_id TEXT PRIMARY KEY NOT NULL, saved_at INTEGER NOT NULL, document TEXT NOT NULL)")
        }
    }
    fun save(project: SceneProject) {
        val document = SceneProjectJsonCodec.encode(project)
        val values = ContentValues().apply {
            put("id", project.id)
            put("name", project.name)
            put("updated_at", project.updatedAtEpochMillis)
            put("document", document)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = db.query(
                "projects", arrayOf("document", "updated_at"), "id=?",
                arrayOf(project.id), null, null, null, "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
            }
            val recovery = ContentValues().apply {
                put("project_id", project.id)
                put("saved_at", previous?.second ?: project.updatedAtEpochMillis)
                put("document", previous?.first ?: document)
            }
            db.insertWithOnConflict("project_recovery", null, recovery, SQLiteDatabase.CONFLICT_REPLACE)
            check(db.insertWithOnConflict("projects", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                "Unable to persist scene project"
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Atomically converts every scene and stores the previous document as the
     * recovery snapshot. A failed encode/write rolls the entire transaction back.
     */
    fun convertProjectOrientation(projectId: String, orientation: CanvasOrientation): SceneProject {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldDocument = db.query(
                "projects", arrayOf("document", "updated_at"), "id=?",
                arrayOf(projectId), null, null, null, "1"
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Scene project not found: $projectId" }
                cursor.getString(0) to cursor.getLong(1)
            }
            val original = SceneProjectJsonCodec.decode(oldDocument.first)
            val convertedGeometry = SceneProjectOrientationConverter.convert(original, orientation)
            val now = maxOf(System.currentTimeMillis(), original.createdAtEpochMillis)
            val converted = convertedGeometry.copy(updatedAtEpochMillis = now)
            val recovery = ContentValues().apply {
                put("project_id", projectId)
                put("saved_at", oldDocument.second)
                put("document", oldDocument.first)
            }
            check(db.insertWithOnConflict("project_recovery", null, recovery, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
                "Unable to create project recovery snapshot"
            }
            val updated = ContentValues().apply {
                put("name", converted.name)
                put("updated_at", converted.updatedAtEpochMillis)
                put("document", SceneProjectJsonCodec.encode(converted))
            }
            check(db.update("projects", updated, "id=?", arrayOf(projectId)) == 1) {
                "Unable to persist converted project"
            }
            db.setTransactionSuccessful()
            return converted
        } finally {
            db.endTransaction()
        }
    }

    fun get(id: String): SceneProject? = readableDatabase.query("projects",arrayOf("document"),"id=?",arrayOf(id),null,null,null,"1").use {
        if (it.moveToFirst()) SceneProjectJsonCodec.decode(it.getString(0)) else null
    }
    fun listRecent(limit:Int=50):List<ProjectSummary> {
        require(limit in 1..500)
        return readableDatabase.query("projects",arrayOf("id","name","updated_at"),null,null,null,null,"updated_at DESC",limit.toString()).use { c ->
            buildList { while(c.moveToNext()) add(ProjectSummary(c.getString(0),c.getString(1),c.getLong(2))) }
        }
    }
    fun saveRecovery(project: SceneProject, savedAtEpochMillis: Long) {
        require(savedAtEpochMillis >= 0L)
        val values=ContentValues().apply {
            put("project_id",project.id);put("saved_at",savedAtEpochMillis)
            put("document",SceneProjectJsonCodec.encode(project))
        }
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("project_recovery",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun getRecovery(projectId:String): SceneProject? =
        readableDatabase.query("project_recovery",arrayOf("document"),"project_id=?",arrayOf(projectId),null,null,null,"1").use {
            if(it.moveToFirst()) SceneProjectJsonCodec.decode(it.getString(0)) else null
        }
    fun deleteRecovery(projectId:String):Boolean =
        writableDatabase.delete("project_recovery","project_id=?",arrayOf(projectId))>0
    fun delete(id:String):Boolean {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.delete("project_recovery","project_id=?",arrayOf(id))
            val deleted=db.delete("projects","id=?",arrayOf(id))>0
            db.setTransactionSuccessful()
            return deleted
        } finally { db.endTransaction() }
    }
}
data class ProjectSummary(val id:String,val name:String,val updatedAtEpochMillis:Long)
