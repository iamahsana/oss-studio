package com.iamahsana.ossstudio.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * Fixed-capacity single-producer/single-consumer PCM frame queue.
 *
 * One capture callback owns offer(); one mixer thread owns poll(). Frames are
 * copied into preallocated slots. No per-frame objects or locks are created.
 * When full, the newest frame is dropped so the consumer never blocks capture.
 */
class SpscPcmFrameQueue(
    capacity: Int = 8,
    private val maxFrameBytes: Int = 8192
) {
    private class Slot(maxBytes: Int) {
        val data = ByteArray(maxBytes)
        var size: Int = 0
        var timestampMicros: Long = 0L
    }

    class FrameInfo {
        var size: Int = 0
            internal set
        var timestampMicros: Long = 0L
            internal set
    }

    init {
        require(capacity > 0) { "Queue capacity must be positive" }
        require(maxFrameBytes > 0) { "Maximum frame size must be positive" }
    }

    private val slots = Array(capacity) { Slot(maxFrameBytes) }

    // Volatile publication ensures slot contents are visible across producer
    // and consumer threads. This queue is intentionally SPSC, not MPSC/MPMC.
    @Volatile
    private var writeIndex = 0L

    @Volatile
    private var readIndex = 0L

    private val dropped = AtomicLong(0L)
    private val oversized = AtomicLong(0L)

    val droppedFrames: Long
        get() = dropped.get()

    val oversizedFrames: Long
        get() = oversized.get()

    /** Approximate queued frame count; exact under the single-consumer model. */
    val queuedFrames: Int
        get() = (writeIndex - readIndex).coerceIn(0L, slots.size.toLong()).toInt()

    /**
     * Publish one PCM frame. Returns false when the queue is full or the frame
     * exceeds the preallocated slot size. Invalid array ranges throw.
     */
    fun offer(source: ByteArray, offset: Int, size: Int, timestampMicros: Long): Boolean {
        require(offset >= 0 && size >= 0 && offset <= source.size - size) {
            "Invalid PCM source range"
        }
        if (size > maxFrameBytes) {
            oversized.incrementAndGet()
            dropped.incrementAndGet()
            return false
        }

        val write = writeIndex
        if (write - readIndex >= slots.size) {
            dropped.incrementAndGet()
            return false
        }

        val slot = slots[(write % slots.size).toInt()]
        System.arraycopy(source, offset, slot.data, 0, size)
        slot.size = size
        slot.timestampMicros = timestampMicros
        // Publish only after the payload and metadata are fully written.
        writeIndex = write + 1L
        return true
    }

    /**
     * Copy the oldest frame into caller-owned storage. Returns false when
     * empty or when [target] is too small; a too-small target does not consume
     * the queued frame.
     */
    fun poll(target: ByteArray, info: FrameInfo): Boolean {
        val read = readIndex
        if (read >= writeIndex) return false

        val slot = slots[(read % slots.size).toInt()]
        if (target.size < slot.size) return false

        System.arraycopy(slot.data, 0, target, 0, slot.size)
        info.size = slot.size
        info.timestampMicros = slot.timestampMicros
        // Release the slot only after the consumer has copied all its bytes.
        readIndex = read + 1L
        return true
    }
}
