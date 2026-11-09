package com.iamahsana.ossstudio.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Allocation-free PCM16 stereo mixer core.
 *
 * Buffers use interleaved signed 16-bit little-endian PCM (L, R). This matches
 * RootEncoder's encoder-facing BufferAudioSource ByteArray contract, allowing
 * the LIVE bus to be handed off without a format conversion or per-block copy.
 * The caller owns capture queues, timestamps/resampling and output sinks.
 */
data class ChannelMixConfig(
    val gain: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    /** Stereo balance: -1 = left, 0 = center, +1 = right. */
    val pan: Float = 0f,
    val routeToLive: Boolean = true,
    val routeToMonitor: Boolean = true
) {
    init {
        require(gain in 0f..2f) { "Channel gain must be between 0% and 200%" }
        require(pan in -1f..1f) { "Pan must be between -1 and +1" }
    }
}

/**
 * Reusable output arrays are supplied by the caller so this method performs no
 * per-block allocations. Meter arrays are indexed by input channel.
 */
class PcmMixerCore(
    private val sampleRateHz: Int = 48_000,
    private val limiterCeiling: Float = 0.95f,
    private val limiterReleaseMs: Float = 100f
) {
    init {
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(limiterCeiling in 0.5f..1f) { "Limiter ceiling must be between 50% and 100%" }
        require(limiterReleaseMs > 0f) { "Limiter release must be positive" }
    }

    @Volatile
    var liveLimiterGain: Float = 1f
        private set

    @Volatile
    var monitorLimiterGain: Float = 1f
        private set
    /**
     * Mix [frameCount] stereo frames from [inputs] into independent buses.
     * Each frame occupies four bytes: L low/high, then R low/high.
     *
     * A null input represents a source that is not currently producing PCM.
     * Output buffers and meter arrays must be preallocated by the caller.
     */
    fun mixInto(
        inputs: Array<out ByteArray?>,
        configs: Array<ChannelMixConfig>,
        frameCount: Int,
        liveOutput: ByteArray,
        monitorOutput: ByteArray,
        liveMasterGain: Float = 1f,
        monitorMasterGain: Float = 1f,
        channelPeak: FloatArray,
        channelRms: FloatArray,
        livePeak: FloatArray,
        liveRms: FloatArray,
        monitorPeak: FloatArray,
        monitorRms: FloatArray
    ) {
        require(frameCount >= 0) { "Frame count cannot be negative" }
        require(inputs.size == configs.size) { "Each input must have one channel config" }
        require(liveMasterGain in 0f..2f && monitorMasterGain in 0f..2f) {
            "Master gain must be between 0% and 200%"
        }
        require(frameCount <= Int.MAX_VALUE / BYTES_PER_FRAME) { "Frame count is too large" }
        val byteCount = frameCount * BYTES_PER_FRAME
        require(liveOutput.size >= byteCount && monitorOutput.size >= byteCount) {
            "Output buffers are too small"
        }
        require(channelPeak.size >= inputs.size && channelRms.size >= inputs.size) {
            "Per-channel meter arrays are too small"
        }
        require(livePeak.isNotEmpty() && liveRms.isNotEmpty() &&
            monitorPeak.isNotEmpty() && monitorRms.isNotEmpty()) {
            "Bus meter arrays must have at least one element"
        }
        for (i in inputs.indices) {
            val input = inputs[i]
            require(input == null || input.size >= byteCount) { "Input buffer $i is too small" }
        }

        var liveHasSolo = false
        var monitorHasSolo = false
        for (config in configs) {
            if (!config.muted && config.solo) {
                if (config.routeToLive) liveHasSolo = true
                if (config.routeToMonitor) monitorHasSolo = true
            }
        }

        for (channelIndex in inputs.indices) {
            val input = inputs[channelIndex]
            val config = configs[channelIndex]
            if (input == null || config.muted) {
                channelPeak[channelIndex] = 0f
                channelRms[channelIndex] = 0f
                continue
            }

            val leftGain = config.gain * if (config.pan > 0f) 1f - config.pan else 1f
            val rightGain = config.gain * if (config.pan < 0f) 1f + config.pan else 1f
            var peak = 0f
            var sumSquares = 0.0
            var byteIndex = 0
            while (byteIndex < byteCount) {
                val left = readPcm16(input, byteIndex).toFloat() / PCM_SCALE * leftGain
                val right = readPcm16(input, byteIndex + 2).toFloat() / PCM_SCALE * rightGain
                val leftMagnitude = abs(left)
                val rightMagnitude = abs(right)
                if (leftMagnitude > peak) peak = leftMagnitude
                if (rightMagnitude > peak) peak = rightMagnitude
                sumSquares += left.toDouble() * left.toDouble()
                sumSquares += right.toDouble() * right.toDouble()
                byteIndex += BYTES_PER_FRAME
            }
            channelPeak[channelIndex] = peak.coerceAtMost(1f)
            channelRms[channelIndex] =
                if (byteCount == 0) 0f else sqrt(sumSquares / (frameCount * CHANNELS)).toFloat().coerceAtMost(1f)
        }

        // Measure the complete block before writing samples. This provides
        // block-level look-ahead without adding a second PCM delay line.
        var rawLivePeak = 0f
        var rawMonitorPeak = 0f
        var peakByteIndex = 0
        while (peakByteIndex < byteCount) {
            var liveLeft = 0f
            var liveRight = 0f
            var monitorLeft = 0f
            var monitorRight = 0f

            for (channelIndex in inputs.indices) {
                val input = inputs[channelIndex] ?: continue
                val config = configs[channelIndex]
                if (config.muted) continue
                val liveAllowed = config.routeToLive && (!liveHasSolo || config.solo)
                val monitorAllowed = config.routeToMonitor && (!monitorHasSolo || config.solo)
                if (!liveAllowed && !monitorAllowed) continue

                val leftGain = config.gain * if (config.pan > 0f) 1f - config.pan else 1f
                val rightGain = config.gain * if (config.pan < 0f) 1f + config.pan else 1f
                if (liveAllowed) {
                    liveLeft += readPcm16(input, peakByteIndex).toFloat() / PCM_SCALE * leftGain
                    liveRight += readPcm16(input, peakByteIndex + 2).toFloat() / PCM_SCALE * rightGain
                }
                if (monitorAllowed) {
                    monitorLeft += readPcm16(input, peakByteIndex).toFloat() / PCM_SCALE * leftGain
                    monitorRight += readPcm16(input, peakByteIndex + 2).toFloat() / PCM_SCALE * rightGain
                }
            }

            liveLeft *= liveMasterGain
            liveRight *= liveMasterGain
            monitorLeft *= monitorMasterGain
            monitorRight *= monitorMasterGain
            rawLivePeak = maxOf(rawLivePeak, abs(liveLeft), abs(liveRight))
            rawMonitorPeak = maxOf(rawMonitorPeak, abs(monitorLeft), abs(monitorRight))
            peakByteIndex += BYTES_PER_FRAME
        }

        liveLimiterGain = nextLimiterGain(liveLimiterGain, rawLivePeak, frameCount)
        monitorLimiterGain = nextLimiterGain(monitorLimiterGain, rawMonitorPeak, frameCount)

        var liveMax = 0f
        var monitorMax = 0f
        var liveSquares = 0.0
        var monitorSquares = 0.0
        var byteIndex = 0

        while (byteIndex < byteCount) {
            var liveLeft = 0f
            var liveRight = 0f
            var monitorLeft = 0f
            var monitorRight = 0f

            for (channelIndex in inputs.indices) {
                val input = inputs[channelIndex] ?: continue
                val config = configs[channelIndex]
                if (config.muted) continue

                val liveAllowed = config.routeToLive && (!liveHasSolo || config.solo)
                val monitorAllowed = config.routeToMonitor && (!monitorHasSolo || config.solo)
                if (!liveAllowed && !monitorAllowed) continue

                val leftGain = config.gain * if (config.pan > 0f) 1f - config.pan else 1f
                val rightGain = config.gain * if (config.pan < 0f) 1f + config.pan else 1f
                if (liveAllowed) {
                    liveLeft += readPcm16(input, byteIndex).toFloat() / PCM_SCALE * leftGain
                    liveRight += readPcm16(input, byteIndex + 2).toFloat() / PCM_SCALE * rightGain
                }
                if (monitorAllowed) {
                    monitorLeft += readPcm16(input, byteIndex).toFloat() / PCM_SCALE * leftGain
                    monitorRight += readPcm16(input, byteIndex + 2).toFloat() / PCM_SCALE * rightGain
                }
            }

            liveLeft *= liveMasterGain * liveLimiterGain
            liveRight *= liveMasterGain * liveLimiterGain
            monitorLeft *= monitorMasterGain * monitorLimiterGain
            monitorRight *= monitorMasterGain * monitorLimiterGain

            val ll = abs(liveLeft)
            val lr = abs(liveRight)
            val ml = abs(monitorLeft)
            val mr = abs(monitorRight)
            if (ll > liveMax) liveMax = ll
            if (lr > liveMax) liveMax = lr
            if (ml > monitorMax) monitorMax = ml
            if (mr > monitorMax) monitorMax = mr
            liveSquares += liveLeft.toDouble() * liveLeft.toDouble()
            liveSquares += liveRight.toDouble() * liveRight.toDouble()
            monitorSquares += monitorLeft.toDouble() * monitorLeft.toDouble()
            monitorSquares += monitorRight.toDouble() * monitorRight.toDouble()

            writePcm16(liveOutput, byteIndex, toPcm16(liveLeft))
            writePcm16(liveOutput, byteIndex + 2, toPcm16(liveRight))
            writePcm16(monitorOutput, byteIndex, toPcm16(monitorLeft))
            writePcm16(monitorOutput, byteIndex + 2, toPcm16(monitorRight))
            byteIndex += BYTES_PER_FRAME
        }

        val sampleCount = frameCount * CHANNELS
        livePeak[0] = liveMax.coerceAtMost(1f)
        monitorPeak[0] = monitorMax.coerceAtMost(1f)
        liveRms[0] = if (sampleCount == 0) 0f else
            sqrt(liveSquares / sampleCount).toFloat().coerceAtMost(1f)
        monitorRms[0] = if (sampleCount == 0) 0f else
            sqrt(monitorSquares / sampleCount).toFloat().coerceAtMost(1f)
    }

    private fun nextLimiterGain(current: Float, blockPeak: Float, frameCount: Int): Float {
        val target = if (blockPeak > limiterCeiling) limiterCeiling / blockPeak else 1f
        if (target < current) return target
        val releaseSamples = sampleRateHz.toFloat() * limiterReleaseMs / 1_000f
        val releaseCoefficient = exp(-frameCount.toFloat() / releaseSamples)
        return (target + (current - target) * releaseCoefficient).coerceIn(0f, 1f)
    }

    private fun readPcm16(buffer: ByteArray, offset: Int): Int {
        val value = (buffer[offset].toInt() and 0xFF) or (buffer[offset + 1].toInt() shl 8)
        return value.toShort().toInt()
    }

    private fun writePcm16(buffer: ByteArray, offset: Int, value: Short) {
        buffer[offset] = (value.toInt() and 0xFF).toByte()
        buffer[offset + 1] = (value.toInt() shr 8).toByte()
    }

    private fun toPcm16(value: Float): Short {
        val bounded = value.coerceIn(-1f, 1f)
        return (bounded * 32768f).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private companion object {
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 4
        const val PCM_SCALE = 32768f
    }
}
