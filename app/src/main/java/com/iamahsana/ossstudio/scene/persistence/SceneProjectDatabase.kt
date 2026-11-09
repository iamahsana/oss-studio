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
    fun delete(id:String):Boolean=writableDatabase.delete("projects","id=?",arrayOf(id))>0
}
data class ProjectSummary(val id:String,val name:String,val updatedAtEpochMillis:Long)
