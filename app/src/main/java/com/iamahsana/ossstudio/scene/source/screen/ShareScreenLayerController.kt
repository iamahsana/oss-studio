package com.iamahsana.ossstudio.scene.source.screen

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.library.view.GlStreamInterface
import java.util.UUID

/**
 * Routes an authorized MediaProjection into the active RootEncoder GL chain.
 *
 * The projection foreground service owns MediaProjection lifetime. This
 * controller owns only its GL filter and VirtualDisplay attachment. Call
 * detach() when removing the layer; call stop() only when the user explicitly
 * wants the projection itself stopped.
 *
 * All public lifecycle methods must be called on the main thread.
 */
class ShareScreenLayerController(
    private val sourceId: String,
    private val glInterface: GlStreamInterface
) {

    enum class State {
        IDLE,
        WAITING_FOR_GL,
        WAITING_FOR_GL_SURFACE,
        CAPTURING,
        STOPPED,
        FAILED
    }

    @Volatile
    var state: State = State.IDLE
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceFilter: SurfaceFilterRender? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var pendingGlCheck: Runnable? = null
    private var glWaitAttempts = 0
    private var operationId = UUID.randomUUID().toString()

    /**
     * Attach Share Screen to the current scene. If RootEncoder's GL worker is
     * still starting, wait for it for at most five seconds instead of failing
     * immediately. No preview surface is created.
     *
     * scale and position use RootEncoder Sprite percentage units (0..100).
     */
    fun attach(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int,
        scaleX: Float = 100f,
        scaleY: Float = 100f,
        positionX: Float = 0f,
        positionY: Float = 0f
    ) {
        checkMainThread()
        require(width > 0 && height > 0 && densityDpi > 0) {
            "Share Screen dimensions and density must be positive"
        }
        require(scaleX > 0f && scaleY > 0f) { "Share Screen scale must be positive" }
        check(state != State.WAITING_FOR_GL && state != State.WAITING_FOR_GL_SURFACE &&
            state != State.CAPTURING) {
            "Share Screen source is already attached"
        }

        operationId = UUID.randomUUID().toString()
        projection = mediaProjection
        state = State.WAITING_FOR_GL
        glWaitAttempts = 0

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                log(
                    level = OssLogLevel.WARN,
                    event = "projection_stopped_by_system",
                    message = "Android MediaProjection session stopped"
                )
                releaseInternal(stopProjection = false, finalState = State.STOPPED)
            }
        }
        projectionCallback = callback
        mediaProjection.registerCallback(callback, mainHandler)

        val attachWhenReady = object : Runnable {
            override fun run() {
                if (projection !== mediaProjection || state != State.WAITING_FOR_GL) return
                if (!glInterface.isRunning()) {
                    glWaitAttempts++
                    if (glWaitAttempts >= MAX_GL_WAIT_ATTEMPTS) {
                        log(
                            level = OssLogLevel.ERROR,
                            event = "gl_start_timeout",
                            errorCode = "SCREEN_GL_START_TIMEOUT",
                            message = "RootEncoder GL output did not become ready within five seconds"
                        )
                        releaseInternal(stopProjection = false, finalState = State.FAILED)
                        return
                    }
                    mainHandler.postDelayed(this, GL_RETRY_INTERVAL_MS)
                    return
                }
                installSurfaceFilter(
                    mediaProjection, width, height, densityDpi,
                    scaleX, scaleY, positionX, positionY
                )
            }
        }
        pendingGlCheck = attachWhenReady
        attachWhenReady.run()
        log(
            level = OssLogLevel.INFO,
            event = "layer_attach_requested",
            message = "Share Screen attachment queued while waiting for RootEncoder GL readiness"
        )
    }

    private fun installSurfaceFilter(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int,
        scaleX: Float,
        scaleY: Float,
        positionX: Float,
        positionY: Float
    ) {
        checkMainThread()
        if (projection !== mediaProjection || state != State.WAITING_FOR_GL) return
        pendingGlCheck = null
        state = State.WAITING_FOR_GL_SURFACE

        val filter = SurfaceFilterRender { surfaceTexture ->
            checkMainThread()
            if (state != State.WAITING_FOR_GL_SURFACE || projection !== mediaProjection) {
                return@SurfaceFilterRender
            }
            try {
                surfaceTexture.setDefaultBufferSize(width, height)
                val targetSurface: Surface = requireNotNull(surfaceFilter?.surface) {
                    "RootEncoder Share Screen surface was not initialized"
                }
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "OSS Share Screen",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    targetSurface,
                    null,
                    mainHandler
                ) ?: error("Android did not create a Share Screen virtual display")

                state = State.CAPTURING
                log(
                    level = OssLogLevel.INFO,
                    event = "capture_started",
                    message = "Share Screen is attached to the scene GL chain"
                )
            } catch (error: Exception) {
                log(
                    level = OssLogLevel.ERROR,
                    event = "capture_start_failed",
                    errorCode = "SCREEN_CAPTURE_START_FAILED",
                    message = error.message ?: "Unable to start Share Screen capture"
                )
                // The foreground service, not this layer controller, owns the
                // projection token and decides when the whole session ends.
                releaseInternal(stopProjection = false, finalState = State.FAILED)
            }
        }.apply {
            setScale(scaleX, scaleY)
            setPosition(positionX, positionY)
        }

        surfaceFilter = filter
        glInterface.addFilter(filter)
        log(
            level = OssLogLevel.INFO,
            event = "gl_filter_queued",
            message = "Share Screen SurfaceFilterRender queued on RootEncoder GL thread"
        )
    }

    fun updateTransform(
        scaleX: Float,
        scaleY: Float,
        positionX: Float,
        positionY: Float
    ) {
        checkMainThread()
        require(scaleX > 0f && scaleY > 0f) { "Share Screen scale must be positive" }
        val filter = surfaceFilter ?: return
        filter.setScale(scaleX, scaleY)
        filter.setPosition(positionX, positionY)
        log(
            level = OssLogLevel.DEBUG,
            event = "layer_transform_updated",
            message = "Share Screen layer transform updated"
        )
    }

    /** Remove only the scene layer; keep the service-owned projection active. */
    fun detach() {
        checkMainThread()
        releaseInternal(stopProjection = false, finalState = State.STOPPED)
    }

    /** User explicitly ends screen capture and the underlying projection. */
    fun stop() {
        checkMainThread()
        releaseInternal(stopProjection = true, finalState = State.STOPPED)
    }

    private fun releaseInternal(stopProjection: Boolean, finalState: State) {
        checkMainThread()
        pendingGlCheck?.let(mainHandler::removeCallbacks)
        pendingGlCheck = null

        val oldProjection = projection
        val oldCallback = projectionCallback
        val oldFilter = surfaceFilter

        projection = null
        projectionCallback = null
        surfaceFilter = null

        runCatching { virtualDisplay?.release() }
            .onFailure {
                log(
                    level = OssLogLevel.WARN,
                    event = "virtual_display_release_failed",
                    errorCode = "SCREEN_VIRTUAL_DISPLAY_RELEASE_FAILED",
                    message = it.message ?: "Virtual display release failed"
                )
            }
        virtualDisplay = null

        if (oldProjection != null && oldCallback != null) {
            runCatching { oldProjection.unregisterCallback(oldCallback) }
        }
        if (oldFilter != null) {
            runCatching { glInterface.removeFilter(oldFilter) }
                .onFailure {
                    log(
                        level = OssLogLevel.ERROR,
                        event = "layer_remove_failed",
                        errorCode = "SCREEN_LAYER_REMOVE_FAILED",
                        message = it.message ?: "Share Screen layer removal failed"
                    )
                }
        }
        if (stopProjection) {
            runCatching { oldProjection?.stop() }
        }

        state = finalState
        log(
            level = if (finalState == State.FAILED) OssLogLevel.ERROR else OssLogLevel.INFO,
            event = "capture_stopped",
            errorCode = if (finalState == State.FAILED) "SCREEN_CAPTURE_FAILED" else null,
            message = "Share Screen capture resources released"
        )
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ShareScreenLayerController must be used from the main thread"
        }
    }

    private fun log(
        level: OssLogLevel,
        event: String,
        errorCode: String? = null,
        message: String
    ) {
        OssLogger.log(
            level = level,
            subsystem = "OSS/SceneSource/Screen",
            component = "ShareScreenLayerController",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(sourceId = sourceId, operationId = operationId)
        )
    }

    companion object {
        private const val GL_RETRY_INTERVAL_MS = 50L
        private const val MAX_GL_WAIT_ATTEMPTS = 100
    }
}
