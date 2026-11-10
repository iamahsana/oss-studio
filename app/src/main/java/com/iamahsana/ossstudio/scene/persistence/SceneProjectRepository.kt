package com.iamahsana.ossstudio.scene.persistence

import com.iamahsana.ossstudio.scene.model.SceneProject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private object SceneProjectIoExecutor {
    val shared: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "oss-project-database").apply { isDaemon = true }
    }
}

class SceneProjectRepository(
    private val database: SceneProjectDatabase,
    private val io: ExecutorService = SceneProjectIoExecutor.shared
) : AutoCloseable {
    fun save(project:SceneProject,done:(Result<Unit>)->Unit={}) = io.execute { done(runCatching { database.save(project) }) }
    fun load(id:String,done:(Result<SceneProject?>)->Unit) = io.execute { done(runCatching { database.get(id) }) }
    fun recent(limit:Int=50,done:(Result<List<ProjectSummary>>)->Unit) = io.execute { done(runCatching { database.listRecent(limit) }) }
    fun delete(id:String,done:(Result<Boolean>)->Unit) = io.execute { done(runCatching { database.delete(id) }) }
    override fun close() { if (io !== SceneProjectIoExecutor.shared) io.shutdown() }
}
