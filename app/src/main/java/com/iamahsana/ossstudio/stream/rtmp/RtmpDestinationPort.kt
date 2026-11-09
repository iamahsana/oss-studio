package com.iamahsana.ossstudio.stream.rtmp

import android.media.MediaCodec
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import java.nio.ByteBuffer

/**
 * Boundary used by the multi-destination fan-out manager.
 * Implementations must enqueue/copy encoded buffers before returning.
 */
interface RtmpDestinationPort {
    val destinationId: String
    val isStreaming: Boolean

    fun setVideoCodec(codec: VideoCodec)
    fun setAudioCodec(codec: AudioCodec)
    fun setVideoFormat(width: Int, height: Int, fps: Int)
    fun setAudioFormat(sampleRate: Int, stereo: Boolean)
    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?)
    fun connect(endpoint: String)
    fun disconnect()
    fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
}
