package com.iamahsana.ossstudio.audio

import java.util.Arrays

/**
 * Aligns timestamped, already-resampled PCM16 stereo source frames onto a
 * shared output timeline. One queue is supplied per source channel.
 *
 * All scratch buffers are allocated once. Gaps become silence, frames that
 * ended before the requested block are discarded and counted, and frames
 * spanning multiple blocks are retained until fully consumed.
 */
class PcmTimestampAligner(
    private val queues: Array<SpscPcmFrameQueue>,
    private val sampleRateHz: Int = 48_000,
    private val maxFrameBytes: Int = 8192
) {
    init {
        require(queues.isNotEmpty()) { "At least one PCM source queue is required" }
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(maxFrameBytes >= BYTES_PER_FRAME) { "Maximum frame size is too small" }
    }

    private val currentBuffers = Array(queues.size) { ByteArray(maxFrameBytes) }
    private val currentInfo = Array(queues.size) { SpscPcmFrameQueue.FrameInfo() }
    private val hasCurrent = BooleanArray(queues.size)
    private val late = LongArray(queues.size)
    private val invalid = LongArray(queues.size)
    private val missing = LongArray(queues.size)

    val channelCount: Int
        get() = queues.size

    fun lateFrames(channelIndex: Int): Long = late[channelIndex]
    fun invalidFrames(channelIndex: Int): Long = invalid[channelIndex]
    fun missingBlocks(channelIndex: Int): Long = missing[channelIndex]

    /**
     * Fill one reusable PCM block per source channel, aligned to
     * [blockStartMicros]. [outputBuffers] must be preallocated and sized for
     * frameCount * 4 bytes. Every output is zero-filled before source overlap
     * is copied, so missing regions are deterministic silence.
     *
     * Source queues must contain PCM16 little-endian stereo frames already
     * normalized to [sampleRateHz]. Resampling is deliberately a separate
     * source-adapter responsibility.
     */
    fun fillBlock(
        blockStartMicros: Long,
        frameCount: Int,
        outputBuffers: Array<ByteArray>
    ) {
        require(frameCount >= 0) { "Frame count cannot be negative" }
        require(frameCount <= Int.MAX_VALUE / BYTES_PER_FRAME) { "Frame count is too large" }
        require(outputBuffers.size == queues.size) { "One output buffer is required per source" }

        val blockBytes = frameCount * BYTES_PER_FRAME
        val blockEndMicros = blockStartMicros +
            frameCount.toLong() * MICROS_PER_SECOND / sampleRateHz

        for (channel in queues.indices) {
            val output = outputBuffers[channel]
            require(output.size >= blockBytes) { "Output buffer $channel is too small" }
            Arrays.fill(output, 0, blockBytes, 0.toByte())

            var copiedAny = false
            while (true) {
                if (!hasCurrent[channel]) {
                    if (!queues[channel].poll(currentBuffers[channel], currentInfo[channel])) break
                    hasCurrent[channel] = true
                    if (currentInfo[channel].size == 0 ||
                        currentInfo[channel].size % BYTES_PER_FRAME != 0) {
                        invalid[channel]++
                        hasCurrent[channel] = false
                        continue
                    }
                }

                val info = currentInfo[channel]
                val sourceFrames = info.size / BYTES_PER_FRAME
                val sourceEndMicros = info.timestampMicros +
                    sourceFrames.toLong() * MICROS_PER_SECOND / sampleRateHz

                if (sourceEndMicros <= blockStartMicros) {
                    late[channel]++
                    hasCurrent[channel] = false
                    continue
                }
                if (info.timestampMicros >= blockEndMicros) break

                val overlapStart = maxOf(blockStartMicros, info.timestampMicros)
                val sourceStartFrame = (
                    (overlapStart - info.timestampMicros) * sampleRateHz / MICROS_PER_SECOND
                    ).toInt().coerceIn(0, sourceFrames)
                val outputStartFrame = (
                    (overlapStart - blockStartMicros) * sampleRateHz / MICROS_PER_SECOND
                    ).toInt().coerceIn(0, frameCount)
                val framesToCopy = minOf(
                    sourceFrames - sourceStartFrame,
                    frameCount - outputStartFrame
                )

                if (framesToCopy > 0) {
                    System.arraycopy(
                        currentBuffers[channel],
                        sourceStartFrame * BYTES_PER_FRAME,
                        output,
                        outputStartFrame * BYTES_PER_FRAME,
                        framesToCopy * BYTES_PER_FRAME
                    )
                    copiedAny = true
                }

                if (sourceEndMicros <= blockEndMicros) {
                    hasCurrent[channel] = false
                } else {
                    // This frame continues into the next output block.
                    break
                }
            }

            if (!copiedAny) missing[channel]++
        }
    }

    private companion object {
        const val BYTES_PER_FRAME = 4
        const val MICROS_PER_SECOND = 1_000_000L
    }
}
