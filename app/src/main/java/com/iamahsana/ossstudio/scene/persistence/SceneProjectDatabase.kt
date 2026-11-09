package com.iamahsana.ossstudio.scene.persistence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.iamahsana.ossstudio.scene.model.SceneProject

class SceneProjectDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext,"oss_projects.db",null,1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projects(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,updated_at INTEGER NOT NULL,document TEXT NOT NULL)")
        db.execSQL("CREATE INDEX index_projects_updated_at ON projects(updated_at DESC)")
        db.execSQL("CREATE TABLE project_recovery(project_id TEXT PRIMARY KEY NOT NULL, saved_at INTEGER NOT NULL, document TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned migrations are added here before incrementing DATABASE_VERSION.
    }
    fun save(project: SceneProject) {
        val values=ContentValues().apply {
            put("id",project.id);put("name",project.name);put("updated_at",project.updatedAtEpochMillis)
            put("document",SceneProjectJsonCodec.encode(project))
        }
        val db=writableDatabase
        db.beginTransaction()
        try { db.insertWithOnConflict("projects",null,values,SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful() }
        finally { db.endTransaction() }
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
