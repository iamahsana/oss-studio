package com.iamahsana.ossstudio.stream.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.iamahsana.ossstudio.audio.OssAudioMixerSession
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import com.iamahsana.ossstudio.scene.source.screen.ShareScreenLayerController
import com.iamahsana.ossstudio.scene.source.screen.ShareScreenSessionService
import com.iamahsana.ossstudio.stream.AudioStopStatus
import com.iamahsana.ossstudio.stream.LiveSessionController
import com.iamahsana.ossstudio.stream.rtmp.RtmpStreamSession
import com.pedro.encoder.input.sources.audio.BufferAudioSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android adapter joining service-owned MediaProjection, RootEncoder RTMP,
 * independent OSS audio buses, and the Share Screen GL layer.
 *
 * No audio source-mode picker exists: the mixer starts every available core
 * channel and reports unavailable channels independently.
 */
class AndroidLiveRuntimeFactory(
    context: Context,
    private val projectionService: () -> ShareScreenSessionService?,
    private val streamConfig: RtmpStreamSession.Config = RtmpStreamSession.Config()
) : LiveSessionController.RuntimeFactory {

    private val appContext = context.applicationContext

    override fun create(): LiveSessionController.Runtime {
        return AndroidLiveRuntime(
            appContext = appContext,
            projectionService = projectionService,
            streamConfig = streamConfig
        )
    }

    private class AndroidLiveRuntime(
        private val appContext: Context,
        private val projectionService: () -> ShareScreenSessionService?,
        private val streamConfig: RtmpStreamSession.Config
    ) : LiveSessionController.Runtime {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val runtimeId = UUID.randomUUID().toString()
        private val audioStopRequested = AtomicBoolean(false)

        private var screenLayer: ShareScreenLayerController? = null
        private var screenCheck: Runnable? = null
        private var screenCheckAttempts = 0
        private var released = false
        private var streamListener: LiveSessionController.RtmpListener? = null
        private var streamConnectedReported = false
        private var streamFailureReported = false
        private var rtmpConnected = false
        private var audioReady = false

        private var stream: RtmpStreamSession? = null
        private var audioMixerSession: OssAudioMixerSession? = null

        override fun prepare(): Boolean {
            checkMainThread()
            val bufferAudioSource = BufferAudioSource()
            val newStream = RtmpStreamSession(
                context = appContext,
                listener = RtmpStreamSession.Listener { state, detail ->
                    dispatchMain { onRtmpStateChanged(state, detail) }
                },
                audioSource = bufferAudioSource
            )

            if (!newStream.prepare(streamConfig)) {
                runCatching { newStream.release() }
                log(
                    OssLogLevel.ERROR,
                    "encoder_prepare_failed",
                    "RootEncoder could not prepare video/audio codecs",
                    "LIVE_ENCODER_PREPARE_FAILED"
                )
                return false
            }

            stream = newStream
            audioMixerSession = OssAudioMixerSession(
                context = appContext,
                bufferAudioSource = bufferAudioSource
            ) { snapshot ->
                log(
                    if (snapshot.hasAnySource) OssLogLevel.INFO else OssLogLevel.WARN,
                    "audio_capture_status",
                    "Audio sources: mic=${snapshot.microphone.state}, internal=${snapshot.internalAudio.state}",
                    snapshot.microphone.errorCode ?: snapshot.internalAudio.errorCode
                )
            }
            log(
                OssLogLevel.INFO,
                "runtime_prepared",
                "RootEncoder prepared with OSS BufferAudioSource; independent mixer is ready to start"
            )
            return true
        }

        override fun startRtmp(
            endpoint: String,
            listener: LiveSessionController.RtmpListener
        ) {
            checkMainThread()
            streamListener = listener
            streamConnectedReported = false
            streamFailureReported = false
            rtmpConnected = false
            audioReady = false
            audioStopRequested.set(false)

            checkNotNull(stream) { "RTMP pipeline was not prepared" }.start(endpoint)

            // RootEncoder starts BufferAudioSource as part of startStream().
            // Start the independent capture/mixer graph afterward so it writes
            // into an initialized, running encoder source.
            if (audioStopRequested.get()) return
            val mixer = checkNotNull(audioMixerSession) { "OSS audio mixer was not prepared" }
            val projection = projectionService()?.getActiveProjection()
            Thread({
                if (audioStopRequested.get()) return@Thread
                val ready = runCatching { mixer.start(projection) }.getOrElse { error ->
                    if (!audioStopRequested.get()) {
                        log(
                            OssLogLevel.ERROR,
                            "audio_graph_start_exception",
                            "OSS audio graph startup threw an exception",
                            "AUDIO_GRAPH_START_EXCEPTION",
                            error
                        )
                    }
                    false
                }
                dispatchMain {
                    if (released || audioStopRequested.get()) return@dispatchMain
                    if (!ready) {
                        reportFailureOnce()
                    } else {
                        audioReady = true
                        reportConnectedIfReady()
                    }
                }
            }, "oss-audio-graph-start").apply {
                isDaemon = true
                start()
            }
        }

        private fun onRtmpStateChanged(state: RtmpStreamSession.State, detail: String?) {
            if (released || audioStopRequested.get()) return
            when (state) {
                RtmpStreamSession.State.LIVE -> {
                    rtmpConnected = true
                    reportConnectedIfReady()
                }
                RtmpStreamSession.State.FAILED -> reportFailureOnce()
                else -> Unit
            }
        }

        private fun reportConnectedIfReady() {
            if (rtmpConnected && audioReady && !streamConnectedReported && !streamFailureReported) {
                streamConnectedReported = true
                log(
                    OssLogLevel.INFO,
                    "live_prerequisites_ready",
                    "RTMP destination and OSS audio mixer are both ready"
                )
                streamListener?.onConnected()
            }
        }

        private fun reportFailureOnce() {
            if (streamFailureReported || audioStopRequested.get()) return
            streamFailureReported = true
            log(
                OssLogLevel.ERROR,
                "live_prerequisite_failed",
                "RTMP connection or OSS audio mixer failed to become ready",
                "LIVE_PREREQUISITE_FAILED"
            )
            streamListener?.onFailure()
        }

        override fun attachScreen(listener: LiveSessionController.ScreenListener) {
            checkMainThread()
            val projection = projectionService()?.getActiveProjection()
            if (projection == null) {
                log(
                    OssLogLevel.ERROR,
                    "projection_unavailable",
                    "Cannot attach Share Screen because the foreground service has no active projection",
                    "SCREEN_PROJECTION_UNAVAILABLE"
                )
                listener.onFailure()
                return
            }

            try {
                val layer = ShareScreenLayerController(
                    sourceId = "share-screen-$runtimeId",
                    glInterface = checkNotNull(stream) { "RTMP pipeline was not prepared" }.glInterface()
                )
                screenLayer = layer
                screenCheckAttempts = 0
                layer.attach(
                    mediaProjection = projection,
                    width = streamConfig.width,
                    height = streamConfig.height,
                    densityDpi = appContext.resources.displayMetrics.densityDpi
                )
                watchScreenReadiness(layer, listener)
            } catch (error: Exception) {
                log(
                    OssLogLevel.ERROR,
                    "screen_attach_exception",
                    error.message ?: "Share Screen attach failed",
                    "SCREEN_SOURCE_ATTACH_FAILED",
                    error
                )
                listener.onFailure()
            }
        }

        private fun watchScreenReadiness(
            layer: ShareScreenLayerController,
            listener: LiveSessionController.ScreenListener
        ) {
            val check = object : Runnable {
                override fun run() {
                    if (released || screenLayer !== layer) return
                    when (layer.state) {
                        ShareScreenLayerController.State.CAPTURING -> {
                            screenCheck = null
                            log(
                                OssLogLevel.INFO,
                                "screen_source_ready",
                                "Share Screen VirtualDisplay is attached to RootEncoder GL"
                            )
                            listener.onReady()
                        }
                        ShareScreenLayerController.State.FAILED,
                        ShareScreenLayerController.State.STOPPED -> {
                            screenCheck = null
                            listener.onFailure()
                        }
                        else -> {
                            screenCheckAttempts++
                            if (screenCheckAttempts >= MAX_SCREEN_CHECKS) {
                                screenCheck = null
                                log(
                                    OssLogLevel.ERROR,
                                    "screen_attach_timeout",
                                    "Share Screen did not become ready before adapter timeout",
                                    "SCREEN_SOURCE_READY_TIMEOUT"
                                )
                                listener.onFailure()
                            } else {
                                mainHandler.postDelayed(this, SCREEN_CHECK_INTERVAL_MS)
                            }
                        }
                    }
                }
            }
            screenCheck = check
            mainHandler.post(check)
        }

        override fun detachScreen() {
            checkMainThread()
            screenCheck?.let(mainHandler::removeCallbacks)
            screenCheck = null
            screenLayer?.detach()
            screenLayer = null
        }

        override fun stopRtmp() {
            checkMainThread()
            stream?.stop()
        }

        override fun stopAudio(completion: (AudioStopStatus) -> Unit) {
            checkMainThread()
            audioStopRequested.set(true)
            val mixer = audioMixerSession ?: run {
                completion(AudioStopStatus(true, true, true))
                return
            }
            mixer.stopAsync { result ->
                dispatchMain {
                    if (!result.completed) {
                        log(
                            OssLogLevel.ERROR,
                            "audio_cleanup_incomplete",
                            "Audio cleanup status: mixer=${result.mixerStopped}, capture=${result.captureStopped}, monitor=${result.monitorStopped}",
                            "AUDIO_GRAPH_CLEANUP_PENDING"
                        )
                    }
                    completion(
                        AudioStopStatus(
                            mixerStopped = result.mixerStopped,
                            captureStopped = result.captureStopped,
                            monitorStopped = result.monitorStopped
                        )
                    )
                }
            }
        }

        override fun stopProjection(): Boolean {
            checkMainThread()
            val service = projectionService() ?: return true
            return if (service.getActiveProjection() == null) {
                true
            } else {
                service.stopProjectionSession()
            }
        }

        override fun releaseRtmp() {
            checkMainThread()
            released = true
            screenCheck?.let(mainHandler::removeCallbacks)
            screenCheck = null
            stream?.release()
            stream = null
            if (audioMixerSession?.state == OssAudioMixerSession.State.STOPPED) {
                audioMixerSession = null
            }
        }

        private fun dispatchMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action()
            else mainHandler.post(action)
        }

        private fun checkMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "AndroidLiveRuntime lifecycle must run on the main thread"
            }
        }

        private fun log(
            level: OssLogLevel,
            event: String,
            message: String,
            errorCode: String? = null,
            error: Throwable? = null
        ) {
            OssLogger.log(
                level = level,
                subsystem = "OSS/Stream/Core",
                component = "AndroidLiveRuntime",
                event = event,
                message = message,
                errorCode = errorCode,
                context = OssLogContext(operationId = runtimeId),
                throwable = error
            )
        }

        companion object {
            private const val SCREEN_CHECK_INTERVAL_MS = 50L
            private const val MAX_SCREEN_CHECKS = 120
        }
    }
}
