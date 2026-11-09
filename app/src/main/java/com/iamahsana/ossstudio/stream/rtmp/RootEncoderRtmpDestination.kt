package com.iamahsana.ossstudio.stream.rtmp

import android.media.MediaCodec
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Thin OSS adapter around one upstream RootEncoder RTMP client.
 *
 * This class deliberately represents one destination only. Destination fan-out,
 * queue policy, retry coordination, and aggregate LIVE health belong to the
 * higher-level destination manager and are not implied by this adapter.
 */
class RootEncoderRtmpDestination(
    override val destinationId: String,
    private val listener: Listener? = null
) : RtmpDestinationPort, ConnectChecker {

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED,
        AUTH_ERROR
    }

    fun interface Listener {
        fun onStateChanged(destinationId: String, state: State)
    }

    private val client = RtmpClient(this)
    @Volatile private var operationId = UUID.randomUUID().toString()

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    override val isStreaming: Boolean
        get() = client.isStreaming

    override fun setVideoCodec(codec: VideoCodec) {
        client.setVideoCodec(codec)
    }

    override fun setAudioCodec(codec: AudioCodec) {
        client.setAudioCodec(codec)
    }

    override fun setVideoFormat(width: Int, height: Int, fps: Int) {
        client.setVideoResolution(width, height)
        client.setFps(fps)
    }

    override fun setAudioFormat(sampleRate: Int, stereo: Boolean) {
        client.setAudioInfo(sampleRate, stereo)
    }

    override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        client.setVideoInfo(sps, pps, vps)
    }

    /**
     * The endpoint is passed only to RootEncoder and is never included in OSS logs.
     */
    override fun connect(endpoint: String) {
        operationId = UUID.randomUUID().toString()
        log(
            level = OssLogLevel.INFO,
            event = "connect_requested",
            message = "RTMP destination connection requested"
        )
        client.connect(endpoint)
    }

    override fun disconnect() {
        log(
            level = OssLogLevel.INFO,
            event = "disconnect_requested",
            message = "RTMP destination disconnect requested"
        )
        client.disconnect()
    }

    /**
     * Called from the shared encoder callback. RootEncoder copies each buffer
     * into its own destination queue; callers must not mutate the buffer until
     * this method returns.
     */
    override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        client.sendVideo(buffer, info)
    }

    override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        client.sendAudio(buffer, info)
    }

    override fun onConnectionStarted(url: String) {
        updateState(State.CONNECTING)
        log(
            level = OssLogLevel.INFO,
            event = "connection_started",
            message = "RTMP connection started; endpoint redacted"
        )
    }

    override fun onConnectionSuccess() {
        updateState(State.CONNECTED)
        log(
            level = OssLogLevel.INFO,
            event = "connection_succeeded",
            message = "RTMP destination connected"
        )
    }

    override fun onConnectionFailed(reason: String) {
        updateState(State.FAILED)
        log(
            level = OssLogLevel.ERROR,
            event = "connection_failed",
            errorCode = "RTMP_CONNECT_FAILED",
            message = reason
        )
    }

    override fun onDisconnect() {
        updateState(State.DISCONNECTED)
        log(
            level = OssLogLevel.INFO,
            event = "disconnected",
            message = "RTMP destination disconnected"
        )
    }

    override fun onAuthError() {
        updateState(State.AUTH_ERROR)
        log(
            level = OssLogLevel.ERROR,
            event = "authentication_failed",
            errorCode = "RTMP_AUTH_FAILED",
            message = "RTMP destination authentication failed"
        )
    }

    override fun onAuthSuccess() {
        log(
            level = OssLogLevel.INFO,
            event = "authentication_succeeded",
            message = "RTMP destination authentication succeeded"
        )
    }

    private fun updateState(newState: State) {
        state = newState
        try {
            listener?.onStateChanged(destinationId, newState)
        } catch (error: Exception) {
            log(
                level = OssLogLevel.ERROR,
                event = "state_listener_failed",
                errorCode = "RTMP_STATE_LISTENER_FAILED",
                message = error.message ?: "Destination state listener failed"
            )
        }
    }

    private fun log(
        level: OssLogLevel,
        event: String,
        message: String,
        errorCode: String? = null
    ) {
        OssLogger.log(
            level = level,
            subsystem = "OSS/Stream/Destination",
            component = "RootEncoderRtmpDestination",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(
                destinationId = destinationId,
                operationId = operationId
            )
        )
    }
}
