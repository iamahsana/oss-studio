package com.iamahsana.ossstudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmMixerEngineTest {
    @Test fun processesIndependentSourcesIntoSeparateBuses() {
        val mic = SpscPcmFrameQueue(2, 1920)
        val game = SpscPcmFrameQueue(2, 1920)
        assertTrue(mic.offer(pcm(480, 9000), 0, 1920, 0L))
        assertTrue(game.offer(pcm(480, 4000), 0, 1920, 0L))
        val engine = PcmMixerEngine(
            arrayOf(mic, game),
            arrayOf(
                ChannelMixConfig(routeToLive = true, routeToMonitor = false),
                ChannelMixConfig(routeToLive = false, routeToMonitor = true)
            )
        )

        engine.processBlock(0L)

        assertEquals(9000, sample(engine.liveOutput, 0))
        assertEquals(4000, sample(engine.monitorOutput, 0))
        assertTrue(engine.livePeak > 0.27f)
        assertTrue(engine.monitorPeak > 0.12f)
        assertEquals(0, engine.queuedFrames(0))
        assertEquals(0, engine.queuedFrames(1))
    }

    @Test fun updatesChannelControlsWithoutRecreatingEngine() {
        val mic = SpscPcmFrameQueue(2, 1920)
        assertTrue(mic.offer(pcm(480, 10000), 0, 1920, 0L))
        val engine = PcmMixerEngine(arrayOf(mic), arrayOf(ChannelMixConfig()))
        engine.updateChannelConfig(0, ChannelMixConfig(muted = true))

        engine.processBlock(0L)

        assertEquals(0, sample(engine.liveOutput, 0))
        assertEquals(0, sample(engine.monitorOutput, 0))
    }

    @Test fun emptySourcesProduceSilenceOnBothBuses() {
        val engine = PcmMixerEngine(
            arrayOf(SpscPcmFrameQueue(2, 1920)),
            arrayOf(ChannelMixConfig())
        )

        engine.processBlock(0L)

        assertEquals(0, sample(engine.liveOutput, 0))
        assertEquals(0, sample(engine.monitorOutput, 0))
        assertEquals(1L, engine.missingBlocks(0))
    }

    private fun pcm(frames: Int, value: Int): ByteArray {
        val result = ByteArray(frames * 4)
        var offset = 0
        while (offset < result.size) {
            result[offset] = (value and 0xFF).toByte()
            result[offset + 1] = (value shr 8).toByte()
            result[offset + 2] = (value and 0xFF).toByte()
            result[offset + 3] = (value shr 8).toByte()
            offset += 4
        }
        return result
    }

    private fun sample(data: ByteArray, frame: Int): Int {
        val offset = frame * 4
        return ((data[offset].toInt() and 0xFF) or
            (data[offset + 1].toInt() shl 8)).toShort().toInt()
    }
}
