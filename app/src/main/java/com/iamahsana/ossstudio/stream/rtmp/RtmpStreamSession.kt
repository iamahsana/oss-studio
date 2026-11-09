package com.iamahsana.ossstudio.stream.rtmp

import android.content.Context
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.view.GlStreamInterface
import java.util.UUID

/**
 * Owns one RootEncoder RTMP pipeline for the first end-to-end LIVE integration.
 *
 * NoVideoSource deliberately leaves video capture ownership to OSS: Share Screen
 * is inserted into this stream's GL chain. AudioSource is mandatory and must be
 * the OSS mixer-backed BufferAudioSource. There is intentionally no silent or
 * video-only default audio source.
 *
 * All lifecycle methods must be called from the main thread. RootEncoder owns
 * its internal GL/codec/network workers.
 */
class RtmpStreamSession(
    context: Context,
    private val listener: Listener? = null,
    audioSource: AudioSource
) : ConnectChecker {

    enum class State {
        NEW,
        PREPARING,
        PREPARED,
        CONNECTING,
        LIVE,
        STOPPING,
        STOPPED,
        FAILED,
        RELEASED
    }

    data class Config(
        val width: Int = 720,
        val height: Int = 1280,
        val fps: Int = 30,
        val videoBitrate: Int = 2_500_000,
        val audioSampleRate: Int = 48_000,
        val audioStereo: Boolean = true,
        val audioBitrate: Int = 64_000
    )

    fun interface Listener {
        fun onStateChanged(state: State, detail: String?)
    }

    private val appContext = context.applicationContext
    private val stream = RtmpStream(
        appContext,
        this,
        NoVideoSource(),
        audioSource
    )
    private val operationId = UUID.randomUUID().toString()

    @Volatile
    var state: State = State.NEW
        private set

    val isStreaming: Boolean
        get() = stream.isStreaming

    /** GL interface for ShareScreenLayerController; available after prepare(). */
    fun glInterface(): GlStreamInterface {
        check(state == State.PREPARED || state == State.CONNECTING || state == State.LIVE) {
            "RTMP stream must be prepared before accessing its GL interface"
        }
        return stream.getGlInterface()
    }

    /**
     * Prepare codecs only. This does not request screen permission, start
     * MediaProjection, connect RTMP, or start a preview surface.
     */
    fun prepare(config: Config = Config()): Boolean {
        checkMainThread()
        check(state == State.NEW || state == State.STOPPED) {
            "Stream session cannot be prepared from state $state"
        }
        state = State.PREPARING
        notifyState()

        return try {
            val videoReady = stream.prepareVideo(
                config.width,
                config.height,
                config.videoBitrate,
                config.fps
            )
            if (!videoReady) {
                fail("RTMP_VIDEO_ENCODER_PREPARE_FAILED", "Video encoder preparation failed")
                return false
            }

            val audioReady = stream.prepareAudio(
                config.audioSampleRate,
                config.audioStereo,
                config.audioBitrate
            )
            if (!audioReady) {
                stream.release()
                fail("RTMP_AUDIO_ENCODER_PREPARE_FAILED", "Audio encoder preparation failed")
                return false
            }

            state = State.PREPARED
            log(
                OssLogLevel.INFO,
                "encoder_prepared",
                "RootEncoder video and audio codecs prepared; capture and network are not started"
            )
            notifyState()
            true
        } catch (error: Exception) {
            runCatching { stream.release() }
            fail(
                "RTMP_ENCODER_PREPARE_EXCEPTION",
                error.message ?: "Unexpected encoder preparation error",
                error
            )
            false
        }
    }

    /** Start one RTMP destination. The endpoint is never written to OSS logs. */
    fun start(endpoint: String) {
        checkMainThread()
        require(endpoint.startsWith("rtmp://", ignoreCase = true) ||
            endpoint.startsWith("rtmps://", ignoreCase = true)) {
            "Only RTMP/RTMPS endpoints are supported"
        }
        check(state == State.PREPARED || state == State.STOPPED) {
            "Stream session must be prepared before start; current state=$state"
        }
        state = State.CONNECTING
        notifyState()
        log(OssLogLevel.INFO, "stream_start_requested", "RTMP stream start requested; endpoint redacted")
        try {
            stream.startStream(endpoint)
        } catch (error: Exception) {
            fail(
                "RTMP_STREAM_START_EXCEPTION",
                error.message ?: "Unable to start RTMP stream",
                error
            )
            throw error
        }
    }

    /** Stop network streaming; RootEncoder retains its prepared codec config if possible. */
    fun stop() {
        checkMainThread()
        if (!stream.isStreaming) {
            if (state != State.RELEASED && state != State.FAILED) {
                state = State.STOPPED
                notifyState()
            }
            return
        }
        state = State.STOPPING
        notifyState()
        log(OssLogLevel.INFO, "stream_stop_requested", "RTMP stream stop requested")
        try {
            val preparedAgain = stream.stopStream()
            state = if (preparedAgain) State.STOPPED else State.NEW
            if (!preparedAgain) {
                log(
                    OssLogLevel.WARN,
                    "encoder_reprepare_required",
                    "RootEncoder could not restore codecs; prepare is required before next LIVE"
                )
            }
            notifyState()
        } catch (error: Exception) {
            fail(
                "RTMP_STREAM_STOP_EXCEPTION",
                error.message ?: "Unable to stop RTMP stream",
                error
            )
            // Let the lifecycle coordinator record this stage as failed while
            // still proceeding with projection stop and final release.
            throw error
        }
    }

    /** Final teardown. Call after detaching Share Screen and stopping its projection. */
    fun release() {
        checkMainThread()
        if (state == State.RELEASED) return
        val releaseError = runCatching { stream.release() }.exceptionOrNull()
        if (releaseError != null) {
            log(
                OssLogLevel.ERROR,
                "stream_release_failed",
                releaseError.message ?: "RootEncoder resource release failed",
                "RTMP_STREAM_RELEASE_FAILED",
                releaseError
            )
        }
        state = State.RELEASED
        log(OssLogLevel.INFO, "session_released", "RootEncoder stream session release attempted")
        notifyState()
        // Do not hide partial teardown failures from the session coordinator.
        if (releaseError != null) {
            throw IllegalStateException("RootEncoder resource release failed", releaseError)
        }
    }

    override fun onConnectionStarted(url: String) {
        state = State.CONNECTING
        log(OssLogLevel.INFO, "connection_started", "RTMP connection started; endpoint redacted")
        notifyState()
    }

    override fun onConnectionSuccess() {
        state = State.LIVE
        log(OssLogLevel.INFO, "connection_succeeded", "RTMP destination connected")
        notifyState()
    }

    override fun onConnectionFailed(reason: String) {
        fail("RTMP_CONNECT_FAILED", reason)
    }

    override fun onDisconnect() {
        if (state != State.RELEASED) {
            state = State.STOPPED
            log(OssLogLevel.INFO, "connection_disconnected", "RTMP destination disconnected")
            notifyState()
        }
    }

    override fun onAuthError() {
        fail("RTMP_AUTH_FAILED", "RTMP destination authentication failed")
    }

    override fun onAuthSuccess() {
        log(OssLogLevel.INFO, "authentication_succeeded", "RTMP authentication succeeded")
    }

    private fun fail(code: String, message: String, error: Throwable? = null) {
        state = State.FAILED
        log(OssLogLevel.ERROR, "session_failed", message, code, error)
        notifyState(message)
    }

    private fun notifyState(detail: String? = null) {
        runCatching { listener?.onStateChanged(state, detail) }
            .onFailure {
                log(
                    OssLogLevel.ERROR,
                    "state_listener_failed",
                    it.message ?: "RTMP session listener failed",
                    "RTMP_SESSION_LISTENER_FAILED",
                    it
                )
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
            component = "RtmpStreamSession",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(operationId = operationId),
            throwable = error
        )
    }

    private fun checkMainThread() {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "RtmpStreamSession lifecycle must be called from the main thread"
        }
    }
}
