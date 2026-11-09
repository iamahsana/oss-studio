package com.iamahsana.ossstudio.scene.persistence

import com.iamahsana.ossstudio.scene.model.SceneProject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SceneProjectAutosave(
    private val repository:SceneProjectRepository,
    private val debounceMillis:Long=500L,
    private val onSaved:(Long)->Unit={},
    private val onError:(Throwable)->Unit={}
):AutoCloseable {
    private val scheduler=Executors.newSingleThreadScheduledExecutor()
    private val generation=AtomicLong()
    @Volatile private var pending:ScheduledFuture<*>?=null
    @Volatile private var latestProject: SceneProject? = null
    @Volatile private var closed=false
    init { require(debounceMillis in 50L..10_000L) }
    @Synchronized fun schedule(project:SceneProject) {
        check(!closed)
        val version=generation.incrementAndGet()
        latestProject = project
        pending?.cancel(false)
        pending=scheduler.schedule({
            repository.save(project) { result ->
                if(generation.get()==version) result.fold({onSaved(version)},{onError(it)})
            }
        },debounceMillis,TimeUnit.MILLISECONDS)
    }
    /** Persist the newest in-memory snapshot immediately, bypassing the debounce window. */
    @Synchronized fun flush(done: (Result<Unit>) -> Unit = {}) {
        val project = latestProject ?: return done(Result.success(Unit))
        val version = generation.incrementAndGet()
        pending?.cancel(false)
        pending = null
        repository.save(project) { result ->
            if (generation.get() == version) {
                result.fold({ onSaved(version); done(Result.success(Unit)) }, {
                    onError(it)
                    done(Result.failure(it))
                })
            }
        }
    }

    @Synchronized override fun close() {
        if(closed)return
        flush()
        closed=true
        pending?.cancel(false)
        scheduler.shutdown()
    }
}
