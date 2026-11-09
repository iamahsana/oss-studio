package com.iamahsana.ossstudio.stream.android

import android.content.Context
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.stream.LiveSessionController

/** Latest editor project snapshot consumed when a LIVE scene is attached. */
object ActiveSceneProjectRegistry {
    @Volatile
    private var currentProject: SceneProject? = null

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(SceneProject?) -> Unit>()

    val project: SceneProject?
        get() = currentProject

    fun publish(project: SceneProject?) {
        if (currentProject === project) return
        currentProject = project
        listeners.forEach { listener -> runCatching { listener(project) } }
    }

    fun subscribe(listener: (SceneProject?) -> Unit): AutoCloseable {
        listeners.add(listener)
        runCatching { listener(currentProject) }
        return AutoCloseable { listeners.remove(listener) }
    }
}

/** Process-scoped controller survives navigation and Activity recreation. */
object LiveSessionControllerRegistry {
    @Volatile private var controller: LiveSessionController? = null

    @Volatile
    var state: LiveSessionController.State = LiveSessionController.State.IDLE
        private set

    @Volatile
    var detail: String? = null
        private set

    fun getOrCreate(context: Context): LiveSessionController {
        controller?.let { return it }
        return synchronized(this) {
            controller ?: LiveSessionController(
                runtimeFactory = AndroidLiveRuntimeFactory(
                    context = context.applicationContext,
                    projectionService = { ProjectionServiceRegistry.service },
                    sceneProvider = { ActiveSceneProjectRegistry.project }
                ),
                stateListener = { nextState, nextDetail ->
                    state = nextState
                    detail = nextDetail
                }
            ).also { controller = it }
        }
    }
}
