package com.iamahsana.ossstudio.stream

import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import java.util.UUID

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
        fun stopAudio(completion: (AudioStopStatus) -> Unit) {
            completion(AudioStopStatus(true, true, true))
        }
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
    private var cleanupIncomplete = false
    private var sessionId: String? = null

    /** Starts a new session. Endpoint must be supplied by secure UI state. */
    fun start(endpoint: String): Boolean {
        require(endpoint.startsWith("rtmp://", ignoreCase = true) ||
            endpoint.startsWith("rtmps://", ignoreCase = true)) {
            "Only RTMP/RTMPS endpoints are supported"
        }
        check(state == State.IDLE || state == State.STOPPED || state == State.FAILED) {
            "Cannot start LIVE while controller is in state $state"
        }
        check(!cleanupIncomplete) {
            "Cannot start a new LIVE session while previous resource cleanup is incomplete"
        }

        val newSessionId = UUID.randomUUID().toString()
        sessionId = newSessionId
        runtime = null
        teardown = null
        rtmpConnected = false
        screenReady = false
        stopping = false
        setState(State.PREPARING)

        return try {
            val newRuntime = runtimeFactory.create()
            runtime = newRuntime
            teardown = createTeardown(newRuntime)
            log(OssLogLevel.INFO, "runtime_created", "LIVE runtime created")
            check(newRuntime.prepare()) { "Encoder preparation failed" }
            setState(State.STARTING)
            newRuntime.startRtmp(endpoint, object : RtmpListener {
                override fun onConnected() {
                    if (stopping || runtime !== newRuntime) return
                    rtmpConnected = true
                    log(OssLogLevel.INFO, "rtmp_connected", "RTMP destination connected")
                    updateLiveState()
                }

                override fun onFailure() {
                    if (stopping || runtime !== newRuntime) return
                    failAndCleanup("RTMP connection failed", "RTMP_CONNECT_FAILED")
                }
            })
            // Some adapters may report an immediate connection failure from
            // startRtmp(). Never attach a source after teardown has begun.
            if (stopping) return false

            newRuntime.attachScreen(object : ScreenListener {
                override fun onReady() {
                    if (stopping || runtime !== newRuntime) return
                    screenReady = true
                    log(OssLogLevel.INFO, "screen_source_ready", "Share Screen source is ready")
                    updateLiveState()
                }

                override fun onFailure() {
                    if (stopping || runtime !== newRuntime) return
                    failAndCleanup(
                        "Share Screen source failed to attach",
                        "SCREEN_SOURCE_ATTACH_FAILED"
                    )
                }
            })
            !stopping
        } catch (_: Exception) {
            failAndCleanup("LIVE startup failed", "LIVE_STARTUP_FAILED")
            false
        }
    }

    /**
     * Compatibility wrapper. Android audio teardown is asynchronous, so this
     * returns a result only when the runtime completes synchronously. New UI
     * code should use stopAsync() and observe its callback.
     */
    @Deprecated("Use stopAsync() for non-blocking Android audio teardown")
    fun stop(): LiveSessionLifecycleCoordinator.ShutdownResult? {
        var immediateResult: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        stopAsync { immediateResult = it }
        return immediateResult
    }

    /**
     * Asynchronous stop path for Android runtimes. Audio capture and mixer
     * worker teardown completes off-main before RTMP and MediaProjection stop.
     */
    fun stopAsync(onComplete: ((LiveSessionLifecycleCoordinator.ShutdownResult) -> Unit)? = null) {
        val current = teardown ?: run {
            cleanupIncomplete = false
            stopping = false
            setState(State.STOPPED)
            runCatching {
                onComplete?.invoke(LiveSessionLifecycleCoordinator.ShutdownResult(true, emptyList()))
            }
            return
        }
        if (stopping) return
        stopping = true
        cleanupIncomplete = true
        setState(State.STOPPING)
        current.shutdownAsync { result ->
            cleanupIncomplete = !result.completed
            stopping = false
            setState(if (result.completed) State.STOPPED else State.FAILED)
            log(
                if (result.completed) OssLogLevel.INFO else OssLogLevel.ERROR,
                if (result.completed) "session_stopped" else "session_stop_incomplete",
                if (result.completed) "LIVE session stopped" else "LIVE cleanup had failed stages",
                if (result.completed) null else "LIVE_CLEANUP_INCOMPLETE"
            )
            runCatching { onComplete?.invoke(result) }
        }
    }

    private fun updateLiveState() {
        if (rtmpConnected && screenReady && !stopping) {
            setState(State.LIVE)
        }
    }

    private fun failAndCleanup(detail: String, errorCode: String) {
        if (stopping) return
        stopping = true
        cleanupIncomplete = true
        log(OssLogLevel.ERROR, "session_failed", detail, errorCode)
        setState(State.FAILED, detail)
        val current = teardown
        if (current == null) {
            // RuntimeFactory failed before acquiring any resources.
            cleanupIncomplete = false
            stopping = false
            return
        }
        current.shutdownAsync { result ->
            cleanupIncomplete = !result.completed
            stopping = false
            if (!result.completed) {
                log(
                    OssLogLevel.ERROR,
                    "failure_cleanup_incomplete",
                    "LIVE failure cleanup had failed stages",
                    "LIVE_CLEANUP_INCOMPLETE"
                )
            }
        }
    }

    private fun createTeardown(target: Runtime) = LiveSessionLifecycleCoordinator(
        screenLayer = { target.detachScreen() },
        streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
            override fun stop() = target.stopRtmp()
            override fun stopAudio(completion: (AudioStopStatus) -> Unit) = target.stopAudio(completion)
            override fun release() = target.releaseRtmp()
        },
        projectionSession = { target.stopProjection() }
    )

    private fun setState(next: State, detail: String? = null) {
        val previous = state
        state = next
        if (previous != next) {
            log(
                OssLogLevel.DEBUG,
                "state_changed",
                "LIVE state changed: ${previous.name} -> ${next.name}"
            )
        }
        runCatching { stateListener?.invoke(next, detail) }
    }

    private fun log(
        level: OssLogLevel,
        event: String,
        message: String,
        errorCode: String? = null
    ) {
        OssLogger.log(
            level = level,
            subsystem = "OSS/Stream/Core",
            component = "LiveSessionController",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(sessionId = sessionId)
        )
    }
}
