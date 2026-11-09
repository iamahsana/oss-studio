package com.iamahsana.ossstudio.audio

import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import java.util.concurrent.atomic.AtomicLong

/**
 * Zero-format-conversion adapter for RootEncoder PCM capture callbacks.
 *
 * Use one instance and one SPSC queue per RootEncoder source. The callback only
 * validates metadata and copies the PCM payload into its preallocated queue;
 * mixing, logging, routing and output submission happen on the mixer worker.
 */
class RootEncoderPcmCaptureSink(
    val queue: SpscPcmFrameQueue
) : GetMicrophoneData {

    @Volatile
    private var acceptingFrames = false

    private val invalid = AtomicLong(0L)
    private val ignored = AtomicLong(0L)

    val invalidFrames: Long
        get() = invalid.get()

    val ignoredFrames: Long
        get() = ignored.get()

    fun beginCapture() {
        acceptingFrames = true
    }

    /** Stop accepting new PCM before stopping/releasing the upstream source. */
    fun endCapture() {
        acceptingFrames = false
    }

    override fun inputPCMData(frame: Frame) {
        if (!acceptingFrames) {
            ignored.incrementAndGet()
            return
        }

        val buffer = frame.buffer
        val offset = frame.offset
        val size = frame.size
        if (offset < 0 || size <= 0 || offset > buffer.size - size) {
            invalid.incrementAndGet()
            return
        }

        // RootEncoder audio timestamps are in microseconds.
        queue.offer(buffer, offset, size, frame.timeStamp)
    }
}
