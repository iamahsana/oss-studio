package com.iamahsana.ossstudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmMixerCoreTest {
    private val mixer = PcmMixerCore()

    private fun mix(
        inputs: Array<ShortArray?>,
        configs: Array<ChannelMixConfig>,
        liveGain: Float = 1f,
        monitorGain: Float = 1f
    ): Result {
        val frames = inputs.firstOrNull()?.size?.div(2) ?: 0
        val live = ShortArray(frames * 2)
        val monitor = ShortArray(frames * 2)
        val channelPeak = FloatArray(inputs.size)
        val channelRms = FloatArray(inputs.size)
        val livePeak = FloatArray(1)
        val liveRms = FloatArray(1)
        val monitorPeak = FloatArray(1)
        val monitorRms = FloatArray(1)
        mixer.mixInto(
            inputs, configs, frames, live, monitor, liveGain, monitorGain,
            channelPeak, channelRms, livePeak, liveRms, monitorPeak, monitorRms
        )
        return Result(live, monitor, channelPeak, channelRms, livePeak[0], liveRms[0],
            monitorPeak[0], monitorRms[0])
    }

    @Test fun routesLiveAndMonitorIndependently() {
        val result = mix(
            arrayOf(shortArrayOf(12000, 12000), shortArrayOf(6000, 6000)),
            arrayOf(
                ChannelMixConfig(routeToLive = true, routeToMonitor = false),
                ChannelMixConfig(routeToLive = false, routeToMonitor = true)
            )
        )
        assertEquals(12000, result.live[0].toInt())
        assertEquals(6000, result.monitor[0].toInt())
    }

    @Test fun muteRemovesChannelFromBothBuses() {
        val result = mix(
            arrayOf(shortArrayOf(10000, 10000)),
            arrayOf(ChannelMixConfig(muted = true))
        )
        assertEquals(0, result.live[0].toInt())
        assertEquals(0, result.monitor[0].toInt())
    }

    @Test fun soloIsIndependentForEachBus() {
        val result = mix(
            arrayOf(shortArrayOf(10000, 10000), shortArrayOf(5000, 5000)),
            arrayOf(
                ChannelMixConfig(solo = true, routeToLive = true, routeToMonitor = false),
                ChannelMixConfig(routeToLive = true, routeToMonitor = true)
            )
        )
        assertEquals(10000, result.live[0].toInt())
        assertEquals(5000, result.monitor[0].toInt())
    }

    @Test fun gainAndMasterGainAreApplied() {
        val result = mix(
            arrayOf(shortArrayOf(10000, 10000)),
            arrayOf(ChannelMixConfig(gain = 2f)),
            liveGain = 0.5f,
            monitorGain = 0.25f
        )
        assertEquals(10000, result.live[0].toInt())
        assertEquals(5000, result.monitor[0].toInt())
    }

    @Test fun panMovesSignalToSelectedSide() {
        val result = mix(
            arrayOf(shortArrayOf(12000, 12000)),
            arrayOf(ChannelMixConfig(pan = -1f))
        )
        assertEquals(12000, result.live[0].toInt())
        assertEquals(0, result.live[1].toInt())
    }

    @Test fun summingSaturatesInsteadOfWrapping() {
        val result = mix(
            arrayOf(shortArrayOf(30000, 30000), shortArrayOf(30000, 30000)),
            arrayOf(ChannelMixConfig(), ChannelMixConfig())
        )
        assertEquals(Short.MAX_VALUE.toInt(), result.live[0].toInt())
        assertEquals(Short.MAX_VALUE.toInt(), result.live[1].toInt())
    }

    @Test fun nullSourceProducesSilenceAndZeroMeter() {
        val result = mix(
            arrayOf(null),
            arrayOf(ChannelMixConfig())
        )
        assertEquals(0, result.live[0].toInt())
        assertEquals(0f, result.channelPeak[0])
        assertEquals(0f, result.channelRms[0])
    }

    @Test fun metersReportSignalLevels() {
        val result = mix(
            arrayOf(shortArrayOf(16384, 16384)),
            arrayOf(ChannelMixConfig(routeToLive = true, routeToMonitor = false))
        )
        assertTrue(result.channelPeak[0] > 0.49f)
        assertTrue(result.livePeak > 0.49f)
        assertTrue(result.liveRms > 0.49f)
        assertEquals(0f, result.monitorPeak)
    }

    private data class Result(
        val live: ShortArray,
        val monitor: ShortArray,
        val channelPeak: FloatArray,
        val channelRms: FloatArray,
        val livePeak: Float,
        val liveRms: Float,
        val monitorPeak: Float,
        val monitorRms: Float
    )
}
