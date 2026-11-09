package com.iamahsana.ossstudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmMixerCoreTest {
    private val mixer = PcmMixerCore()

    private fun mix(
        inputs: Array<ByteArray?>,
        configs: Array<ChannelMixConfig>,
        liveGain: Float = 1f,
        monitorGain: Float = 1f
    ): Result {
        val frames = inputs.firstOrNull { it != null }?.size?.div(4) ?: 1
        val live = ByteArray(frames * 4)
        val monitor = ByteArray(frames * 4)
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
            arrayOf(pcm(12000, 12000), pcm(6000, 6000)),
            arrayOf(
                ChannelMixConfig(routeToLive = true, routeToMonitor = false),
                ChannelMixConfig(routeToLive = false, routeToMonitor = true)
            )
        )
        assertEquals(12000, sample(result.live, 0))
        assertEquals(6000, sample(result.monitor, 0))
    }

    @Test fun muteRemovesChannelFromBothBuses() {
        val result = mix(arrayOf(pcm(10000, 10000)), arrayOf(ChannelMixConfig(muted = true)))
        assertEquals(0, sample(result.live, 0))
        assertEquals(0, sample(result.monitor, 0))
    }

    @Test fun soloIsIndependentForEachBus() {
        val result = mix(
            arrayOf(pcm(10000, 10000), pcm(5000, 5000)),
            arrayOf(
                ChannelMixConfig(solo = true, routeToLive = true, routeToMonitor = false),
                ChannelMixConfig(routeToLive = true, routeToMonitor = true)
            )
        )
        assertEquals(10000, sample(result.live, 0))
        assertEquals(5000, sample(result.monitor, 0))
    }

    @Test fun gainAndMasterGainAreApplied() {
        val result = mix(
            arrayOf(pcm(10000, 10000)),
            arrayOf(ChannelMixConfig(gain = 2f)),
            liveGain = 0.5f,
            monitorGain = 0.25f
        )
        assertEquals(10000, sample(result.live, 0))
        assertEquals(5000, sample(result.monitor, 0))
    }

    @Test fun panMovesSignalToSelectedSide() {
        val result = mix(arrayOf(pcm(12000, 12000)), arrayOf(ChannelMixConfig(pan = -1f)))
        assertEquals(12000, sample(result.live, 0))
        assertEquals(0, sample(result.live, 1))
    }

    @Test fun summingSaturatesInsteadOfWrapping() {
        val result = mix(
            arrayOf(pcm(30000, 30000), pcm(30000, 30000)),
            arrayOf(ChannelMixConfig(), ChannelMixConfig())
        )
        assertEquals(Short.MAX_VALUE.toInt(), sample(result.live, 0))
        assertEquals(Short.MAX_VALUE.toInt(), sample(result.live, 1))
    }

    @Test fun nullSourceProducesSilenceAndZeroMeter() {
        val result = mix(arrayOf(null), arrayOf(ChannelMixConfig()))
        assertEquals(0, sample(result.live, 0))
        assertEquals(0f, result.channelPeak[0])
        assertEquals(0f, result.channelRms[0])
    }

    @Test fun metersReportSignalLevels() {
        val result = mix(
            arrayOf(pcm(16384, 16384)),
            arrayOf(ChannelMixConfig(routeToLive = true, routeToMonitor = false))
        )
        assertTrue(result.channelPeak[0] > 0.49f)
        assertTrue(result.livePeak > 0.49f)
        assertTrue(result.liveRms > 0.49f)
        assertEquals(0f, result.monitorPeak)
    }

    @Test fun negativePcmSamplesRemainSignedAndLittleEndian() {
        val result = mix(arrayOf(pcm(-12000, -12000)), arrayOf(ChannelMixConfig()))
        assertEquals(-12000, sample(result.live, 0))
        assertEquals(-12000, sample(result.monitor, 1))
    }

    private fun pcm(left: Int, right: Int): ByteArray {
        val data = ByteArray(4)
        putSample(data, 0, left)
        putSample(data, 1, right)
        return data
    }

    private fun putSample(data: ByteArray, sampleIndex: Int, value: Int) {
        val offset = sampleIndex * 2
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = (value shr 8).toByte()
    }

    private fun sample(data: ByteArray, sampleIndex: Int): Int {
        val offset = sampleIndex * 2
        return ((data[offset].toInt() and 0xFF) or (data[offset + 1].toInt() shl 8)).toShort().toInt()
    }

    private data class Result(
        val live: ByteArray,
        val monitor: ByteArray,
        val channelPeak: FloatArray,
        val channelRms: FloatArray,
        val livePeak: Float,
        val liveRms: Float,
        val monitorPeak: Float,
        val monitorRms: Float
    )
}
