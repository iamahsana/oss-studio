package com.iamahsana.ossstudio.stream.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import com.iamahsana.ossstudio.audio.RootEncoderAudioMixer
import com.iamahsana.ossstudio.scene.source.screen.ShareScreenLayerController
import com.iamahsana.ossstudio.scene.source.screen.ShareScreenSessionService
import com.iamahsana.ossstudio.stream.LiveSessionController
import com.iamahsana.ossstudio.stream.rtmp.RtmpStreamSession
import com.pedro.encoder.input.sources.audio.NoAudioSource
import java.util.UUID

/**
 * Android adapter joining the service-owned MediaProjection, RootEncoder RTMP
 * pipeline, and Share Screen GL layer.
 *
 * Video-only operation is deliberately disabled by default. It must be
 * explicitly enabled by a future Studio UI choice until the OSS audio mixer
 * is connected to RootEncoder.
 */
class AndroidLiveRuntimeFactory(
    context: Context,
    private val projectionService: () -> ShareScreenSessionService?,
    private val allowVideoOnly: Boolean = false,
    private val streamConfig: RtmpStreamSession.Config = RtmpStreamSession.Config()
) : LiveSessionController.RuntimeFactory {

    private val appContext = context.applicationContext

    override fun create(): LiveSessionController.Runtime {
        return AndroidLiveRuntime(
            appContext = appContext,
            projectionService = projectionService,
            allowVideoOnly = allowVideoOnly,
            streamConfig = streamConfig
        )
    }

    private class AndroidLiveRuntime(
        private val appContext: Context,
        private val projectionService: () -> ShareScreenSessionService?,
        private val allowVideoOnly: Boolean,
        private val streamConfig: RtmpStreamSession.Config
    ) : LiveSessionController.Runtime {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val runtimeId = UUID.randomUUID().toString()
        private var screenLayer: ShareScreenLayerController? = null
        private var screenCheck: Runnable? = null
        private var screenCheckAttempts = 0
        private var released = false
        private var streamListener: LiveSessionController.RtmpListener? = null
        private var streamConnectedReported = false
        private var streamFailureReported = false

        private var stream: RtmpStreamSession? = null
        private var audioMixer: RootEncoderAudioMixer? = null

        override fun prepare(): Boolean {
            checkMainThread()
            val audioSource = if (allowVideoOnly) {
                log(
                    OssLogLevel.WARN,
                    "video_only_mode_selected",
                    "Explicit video-only mode selected; no audio will be sent"
                )
                NoAudioSource()
            } else {
                val projection = projectionService()?.getActiveProjection()
                if (projection == null) {
                    log(
                        OssLogLevel.ERROR,
                        "audio_projection_unavailable",
                        "Audio mixer requires an active user-approved MediaProjection session",
                        "LIVE_AUDIO_PROJECTION_UNAVAILABLE"
                    )
                    return false
                }
                val mixer = RootEncoderAudioMixer(projection, runtimeId)
                audioMixer = mixer
                mixer.source
            }

            val createdStream = RtmpStreamSession(
                context = appContext,
                listener = RtmpStreamSession.Listener { state, _ ->
                    when (state) {
                        RtmpStreamSession.State.LIVE -> dispatchMain {
                            if (!streamConnectedReported && !released) {
                                streamConnectedReported = true
                                streamListener?.onConnected()
                            }
                        }
                        RtmpStreamSession.State.FAILED -> dispatchMain {
                            if (!streamFailureReported && !released) {
                                streamFailureReported = true
                                streamListener?.onFailure()
                            }
                        }
                        else -> Unit
                    }
                },
                audioSource = audioSource
            )
            stream = createdStream
            val prepared = createdStream.prepare(streamConfig)
            if (!prepared) {
                log(
                    OssLogLevel.ERROR,
                    "stream_prepare_failed",
                    "RootEncoder could not prepare the configured audio/video pipeline",
                    "LIVE_PIPELINE_PREPARE_FAILED"
                )
            }
            return prepared
        }

        override fun startRtmp(
            endpoint: String,
            listener: LiveSessionController.RtmpListener
        ) {
            checkMainThread()
            streamListener = listener
            checkNotNull(stream) { "RTMP pipeline was not prepared" }.start(endpoint)
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
