package com.iamahsana.ossstudio.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Allocation-free PCM16 stereo mixer core.
 *
 * Buffers are interleaved L/R samples. The caller owns capture queues,
 * timestamps/resampling and the encoder/device sinks; this class only applies
 * channel controls and routes frames to the independent LIVE and MONITOR buses.
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
class PcmMixerCore {
    /**
     * Mix [frameCount] stereo frames from [inputs] into independent buses.
     *
     * Each input is nullable to represent a source that is not currently
     * producing PCM. Missing input contributes silence and a zero meter.
     * Output buffers and meter arrays must be preallocated by the caller.
     */
    fun mixInto(
        inputs: Array<ShortArray?>,
        configs: Array<ChannelMixConfig>,
        frameCount: Int,
        liveOutput: ShortArray,
        monitorOutput: ShortArray,
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
        val sampleCount = frameCount * CHANNELS
        require(liveOutput.size >= sampleCount && monitorOutput.size >= sampleCount) {
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
            require(input == null || input.size >= sampleCount) { "Input buffer $i is too small" }
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
            if (input == null || config.muted ||
                (liveHasSolo && (!config.solo || !config.routeToLive) &&
                    monitorHasSolo && (!config.solo || !config.routeToMonitor))) {
                channelPeak[channelIndex] = 0f
                channelRms[channelIndex] = 0f
                continue
            }

            val leftGain = config.gain * if (config.pan > 0f) 1f - config.pan else 1f
            val rightGain = config.gain * if (config.pan < 0f) 1f + config.pan else 1f
            var peak = 0f
            var sumSquares = 0.0
            for (sampleIndex in 0 until sampleCount) {
                val isLeft = sampleIndex and 1 == 0
                val raw = input[sampleIndex].toFloat() / PCM_SCALE
                val gained = raw * if (isLeft) leftGain else rightGain
                val magnitude = abs(gained)
                if (magnitude > peak) peak = magnitude
                sumSquares += gained.toDouble() * gained.toDouble()
            }
            channelPeak[channelIndex] = peak.coerceAtMost(1f)
            channelRms[channelIndex] =
                if (sampleCount == 0) 0f else sqrt(sumSquares / sampleCount).toFloat().coerceAtMost(1f)
        }

        var liveMax = 0f
        var monitorMax = 0f
        var liveSquares = 0.0
        var monitorSquares = 0.0

        for (sampleIndex in 0 until sampleCount) {
            val isLeft = sampleIndex and 1 == 0
            var liveSum = 0f
            var monitorSum = 0f

            for (channelIndex in inputs.indices) {
                val input = inputs[channelIndex] ?: continue
                val config = configs[channelIndex]
                if (config.muted) continue

                val liveAllowed = config.routeToLive &&
                    (!liveHasSolo || config.solo)
                val monitorAllowed = config.routeToMonitor &&
                    (!monitorHasSolo || config.solo)
                if (!liveAllowed && !monitorAllowed) continue

                val panGain = if (isLeft) {
                    if (config.pan > 0f) 1f - config.pan else 1f
                } else {
                    if (config.pan < 0f) 1f + config.pan else 1f
                }
                val value = input[sampleIndex].toFloat() / PCM_SCALE *
                    config.gain * panGain
                if (liveAllowed) liveSum += value
                if (monitorAllowed) monitorSum += value
            }

            liveSum *= liveMasterGain
            monitorSum *= monitorMasterGain
            val liveMagnitude = abs(liveSum)
            val monitorMagnitude = abs(monitorSum)
            if (liveMagnitude > liveMax) liveMax = liveMagnitude
            if (monitorMagnitude > monitorMax) monitorMax = monitorMagnitude
            liveSquares += liveSum.toDouble() * liveSum.toDouble()
            monitorSquares += monitorSum.toDouble() * monitorSum.toDouble()

            liveOutput[sampleIndex] = toPcm16(liveSum)
            monitorOutput[sampleIndex] = toPcm16(monitorSum)
        }

        livePeak[0] = liveMax.coerceAtMost(1f)
        monitorPeak[0] = monitorMax.coerceAtMost(1f)
        liveRms[0] = if (sampleCount == 0) 0f else
            sqrt(liveSquares / sampleCount).toFloat().coerceAtMost(1f)
        monitorRms[0] = if (sampleCount == 0) 0f else
            sqrt(monitorSquares / sampleCount).toFloat().coerceAtMost(1f)
    }

    private fun toPcm16(value: Float): Short {
        val bounded = value.coerceIn(-1f, 1f)
        return (bounded * if (bounded < 0f) 32768f else 32767f).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private companion object {
        const val CHANNELS = 2
        const val PCM_SCALE = 32768f
    }
}
