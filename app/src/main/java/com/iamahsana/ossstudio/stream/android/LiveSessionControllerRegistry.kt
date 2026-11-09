package com.iamahsana.ossstudio.stream.android

import android.content.Context
import com.iamahsana.ossstudio.scene.model.SceneProject
import com.iamahsana.ossstudio.stream.LiveSessionController

/** Latest editor project snapshot consumed when a LIVE scene is attached. */
object ActiveSceneProjectRegistry {
    @Volatile
    var project: SceneProject? = null
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
