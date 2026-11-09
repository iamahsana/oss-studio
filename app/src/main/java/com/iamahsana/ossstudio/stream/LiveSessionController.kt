package com.iamahsana.ossstudio.stream

/**
 * Coordinates one LIVE attempt. A fresh Runtime and teardown coordinator are
 * created for every start, so a stopped session can never reuse released GL,
 * codec, or MediaProjection resources.
 *
 * All lifecycle entry points and Runtime callbacks must be delivered on the
 * main thread. Android adapters are responsible for marshalling callbacks.
 */
class LiveSessionController(
    private val runtimeFactory: RuntimeFactory,
    private val stateListener: ((State, String?) -> Unit)? = null
) {
    enum class State {
        IDLE, PREPARING, STARTING, LIVE, STOPPING, STOPPED, FAILED
    }

    interface RuntimeFactory {
        fun create(): Runtime
    }

    interface Runtime {
        fun prepare(): Boolean
        fun startRtmp(endpoint: String, listener: RtmpListener)
        fun attachScreen(listener: ScreenListener)
        fun detachScreen()
        fun stopRtmp()
        fun stopProjection(): Boolean
        fun releaseRtmp()
    }

    interface RtmpListener {
        fun onConnected()
        fun onFailure()
    }

    interface ScreenListener {
        fun onReady()
        fun onFailure()
    }

    @Volatile
    var state: State = State.IDLE
        private set

    private var runtime: Runtime? = null
    private var teardown: LiveSessionLifecycleCoordinator? = null
    private var rtmpConnected = false
    private var screenReady = false
    private var stopping = false

    /** Starts a new session. Endpoint must be supplied by secure UI state. */
    fun start(endpoint: String): Boolean {
        require(endpoint.startsWith("rtmp://", ignoreCase = true) ||
            endpoint.startsWith("rtmps://", ignoreCase = true)) {
            "Only RTMP/RTMPS endpoints are supported"
        }
        check(state == State.IDLE || state == State.STOPPED || state == State.FAILED) {
            "Cannot start LIVE while controller is in state $state"
        }

        val newRuntime = runtimeFactory.create()
        runtime = newRuntime
        teardown = createTeardown(newRuntime)
        rtmpConnected = false
        screenReady = false
        stopping = false
        setState(State.PREPARING)

        return try {
            check(newRuntime.prepare()) { "Encoder preparation failed" }
            setState(State.STARTING)
            newRuntime.startRtmp(endpoint, object : RtmpListener {
                override fun onConnected() {
                    if (stopping || runtime !== newRuntime) return
                    rtmpConnected = true
                    updateLiveState()
                }

                override fun onFailure() {
                    if (stopping || runtime !== newRuntime) return
                    failAndCleanup("RTMP connection failed")
                }
            })
            newRuntime.attachScreen(object : ScreenListener {
                override fun onReady() {
                    if (stopping || runtime !== newRuntime) return
                    screenReady = true
                    updateLiveState()
                }

                override fun onFailure() {
                    if (stopping || runtime !== newRuntime) return
                    failAndCleanup("Share Screen source failed to attach")
                }
            })
            true
        } catch (error: Exception) {
            failAndCleanup(error.message ?: "LIVE startup failed")
            false
        }
    }

    /** Idempotent for a session; a later start() creates entirely new resources. */
    fun stop(): LiveSessionLifecycleCoordinator.ShutdownResult? {
        val current = teardown ?: run {
            setState(State.STOPPED)
            return null
        }
        if (stopping) return null
        stopping = true
        setState(State.STOPPING)
        val result = current.shutdown()
        setState(if (result.completed) State.STOPPED else State.FAILED)
        return result
    }

    private fun updateLiveState() {
        if (rtmpConnected && screenReady && !stopping) {
            setState(State.LIVE)
        }
    }

    private fun failAndCleanup(detail: String) {
        if (stopping) return
        stopping = true
        setState(State.FAILED, detail)
        teardown?.shutdown()
        // Preserve FAILED as the externally visible startup result, even if
        // cleanup also reports failures. The lifecycle coordinator logs stages.
    }

    private fun createTeardown(target: Runtime) = LiveSessionLifecycleCoordinator(
        screenLayer = { target.detachScreen() },
        streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
            override fun stop() = target.stopRtmp()
            override fun release() = target.releaseRtmp()
        },
        projectionSession = { target.stopProjection() }
    )

    private fun setState(next: State, detail: String? = null) {
        state = next
        runCatching { stateListener?.invoke(next, detail) }
    }
}
