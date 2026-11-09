package com.iamahsana.ossstudio.audio

import com.pedro.encoder.input.sources.audio.BufferAudioSource

/**
 * Narrow adapter from OSS's LIVE PCM bus to RootEncoder's proven AAC input.
 *
 * Construct this with the exact BufferAudioSource instance also injected into
 * RtmpStreamSession. Activate only after RootEncoder prepareAudio succeeds.
 * Call submitBlock from the dedicated mixer worker, never a capture callback.
 */
class RootEncoderBufferAudioOutput(
    private val bufferAudioSource: BufferAudioSource
) : PcmLiveBlockSink {
    @Volatile
    private var active = false

    fun activateAfterEncoderPrepare(): Boolean {
        if (!bufferAudioSource.created) return false
        active = true
        return true
    }

    fun deactivate() {
        active = false
    }

    /**
     * Submit one interleaved PCM16 little-endian block with its producer
     * timestamp in microseconds. Returns bytes accepted by RootEncoder.
     */
    override fun submitBlock(
        pcm: ByteArray,
        timestampMicros: Long,
        offset: Int,
        size: Int
    ): Int {
        require(offset >= 0 && size >= 0 && offset <= pcm.size - size) {
            "Invalid PCM output range"
        }
        if (!active || !bufferAudioSource.created || size == 0) return 0
        return bufferAudioSource.setBuffer(pcm, timestampMicros, offset, size)
    }

    val isActive: Boolean
        get() = active
}
