package com.iamahsana.ossstudio.stream.rtmp

import android.media.MediaCodec
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Fans one already-encoded audio/video stream out to independent RootEncoder
 * RTMP clients. Each client owns its own bounded upstream send queue.
 *
 * No destination is allowed to abort delivery attempts to other destinations.
 * This class does not create encoders, own scene rendering, or persist secrets.
 */
class RtmpDestinationManager {

    private val destinations = ConcurrentHashMap<String, RtmpDestinationPort>()
    private val videoDispatchFailures = AtomicLong(0)
    private val audioDispatchFailures = AtomicLong(0)

    val destinationCount: Int
        get() = destinations.size

    fun add(destination: RtmpDestinationPort): Boolean {
        val previous = destinations.putIfAbsent(destination.destinationId, destination)
        if (previous != null) {
            log(
                level = OssLogLevel.WARN,
                event = "destination_duplicate",
                destinationId = destination.destinationId,
                errorCode = "RTMP_DESTINATION_DUPLICATE",
                message = "Duplicate RTMP destination ID rejected"
            )
            return false
        }
        log(
            level = OssLogLevel.INFO,
            event = "destination_added",
            destinationId = destination.destinationId,
            message = "RTMP destination registered"
        )
        return true
    }

    fun remove(destinationId: String): Boolean {
        val destination = destinations.remove(destinationId) ?: return false
        runCatching { destination.disconnect() }
            .onFailure {
                log(
                    level = OssLogLevel.ERROR,
                    event = "destination_remove_disconnect_failed",
                    destinationId = destinationId,
                    errorCode = "RTMP_DESTINATION_REMOVE_FAILED",
                    message = it.message ?: "Unable to disconnect removed destination"
                )
            }
        log(
            level = OssLogLevel.INFO,
            event = "destination_removed",
            destinationId = destinationId,
            message = "RTMP destination removed"
        )
        return true
    }

    fun getDestinationIds(): Set<String> = destinations.keys.toSet()

    fun connect(destinationId: String, endpoint: String): Boolean {
        val destination = destinations[destinationId] ?: return false
        return runCatching { destination.connect(endpoint) }
            .onFailure {
                log(
                    level = OssLogLevel.ERROR,
                    event = "destination_connect_dispatch_failed",
                    destinationId = destinationId,
                    errorCode = "RTMP_CONNECT_DISPATCH_FAILED",
                    message = it.message ?: "Unable to start RTMP destination connection"
                )
            }.isSuccess
    }

    fun disconnect(destinationId: String): Boolean {
        val destination = destinations[destinationId] ?: return false
        return runCatching { destination.disconnect() }
            .onFailure {
                log(
                    level = OssLogLevel.ERROR,
                    event = "destination_disconnect_dispatch_failed",
                    destinationId = destinationId,
                    errorCode = "RTMP_DISCONNECT_DISPATCH_FAILED",
                    message = it.message ?: "Unable to stop RTMP destination"
                )
            }.isSuccess
    }

    fun setVideoCodec(codec: com.pedro.common.VideoCodec) =
        forEachDestination("set_video_codec") { it.setVideoCodec(codec) }

    fun setAudioCodec(codec: com.pedro.common.AudioCodec) =
        forEachDestination("set_audio_codec") { it.setAudioCodec(codec) }

    fun setVideoFormat(width: Int, height: Int, fps: Int) =
        forEachDestination("set_video_format") { it.setVideoFormat(width, height, fps) }

    fun setAudioFormat(sampleRate: Int, stereo: Boolean) =
        forEachDestination("set_audio_format") { it.setAudioFormat(sampleRate, stereo) }

    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) =
        forEachDestination("set_video_info") { it.setVideoInfo(sps, pps, vps) }

    /**
     * Called from the shared encoder output callback. RootEncoder's BaseSender
     * copies each buffer into its own per-destination bounded queue synchronously.
     * A slow network therefore drains only that destination's queue; queue overflow
     * is accounted for by RootEncoder and does not block this manager on socket I/O.
     */
    fun dispatchVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        forEachConnected("video", videoDispatchFailures) {
            it.sendVideo(buffer, info)
        }
    }

    fun dispatchAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        forEachConnected("audio", audioDispatchFailures) {
            it.sendAudio(buffer, info)
        }
    }

    fun getDispatchFailureCounts(): Pair<Long, Long> =
        videoDispatchFailures.get() to audioDispatchFailures.get()

    fun disconnectAll() {
        destinations.keys.toList().forEach(::disconnect)
    }

    private inline fun forEachDestination(
        operation: String,
        action: (RtmpDestinationPort) -> Unit
    ) {
        destinations.values.forEach { destination ->
            runCatching { action(destination) }
                .onFailure {
                    log(
                        level = OssLogLevel.ERROR,
                        event = "${operation}_failed",
                        destinationId = destination.destinationId,
                        errorCode = "RTMP_DESTINATION_CONFIG_FAILED",
                        message = it.message ?: "Destination configuration failed"
                    )
                }
        }
    }

    private inline fun forEachConnected(
        mediaType: String,
        failures: AtomicLong,
        action: (RtmpDestinationPort) -> Unit
    ) {
        destinations.values.forEach { destination ->
            if (!destination.isStreaming) return@forEach
            runCatching { action(destination) }
                .onFailure {
                    failures.incrementAndGet()
                    log(
                        level = OssLogLevel.ERROR,
                        event = "${mediaType}_dispatch_failed",
                        destinationId = destination.destinationId,
                        errorCode = if (mediaType == "video") {
                            "RTMP_VIDEO_DISPATCH_FAILED"
                        } else {
                            "RTMP_AUDIO_DISPATCH_FAILED"
                        },
                        message = it.message ?: "Encoded media dispatch failed"
                    )
                }
        }
    }

    private fun log(
        level: OssLogLevel,
        event: String,
        destinationId: String? = null,
        errorCode: String? = null,
        message: String
    ) {
        OssLogger.log(
            level = level,
            subsystem = "OSS/Stream/Destination",
            component = "RtmpDestinationManager",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(destinationId = destinationId)
        )
    }
}
