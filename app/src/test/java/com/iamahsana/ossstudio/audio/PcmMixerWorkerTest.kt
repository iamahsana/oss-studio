package com.iamahsana.ossstudio.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmMixerWorkerTest {

    @Test
    fun processesBlocksAndStopsWithoutBlockingCaptureCallbacks() {
        val engine = PcmMixerEngine(
            sourceQueues = arrayOf(SpscPcmFrameQueue()),
            initialConfigs = arrayOf(ChannelMixConfig()),
            framesPerBlock = 480
        )
        val liveBlocks = AtomicInteger()
        val monitorBlocks = AtomicInteger()
        val worker = PcmMixerWorker(
            engine = engine,
            liveSink = PcmLiveBlockSink { pcm, _ ->
                liveBlocks.incrementAndGet()
                pcm.size
            },
            monitorSink = PcmMonitorBlockSink { _, _ ->
                monitorBlocks.incrementAndGet()
                true
            },
            clock = PcmMixerClock { System.nanoTime() / 1_000L }
        )

        assertTrue(worker.start())
        Thread.sleep(45L)
        worker.requestStop()

        assertTrue(worker.awaitStopped(2_000L))
        assertFalse(worker.isRunning)
        assertTrue(liveBlocks.get() > 0)
        assertTrue(monitorBlocks.get() > 0)
    }

    @Test
    fun reportsWorkerFailureAndStopsTheLoop() {
        val engine = PcmMixerEngine(
            sourceQueues = arrayOf(SpscPcmFrameQueue()),
            initialConfigs = arrayOf(ChannelMixConfig()),
            framesPerBlock = 480
        )
        val failure = CountDownLatch(1)
        val worker = PcmMixerWorker(
            engine = engine,
            liveSink = PcmLiveBlockSink { _, _ ->
                throw IllegalStateException("test sink failure")
            },
            monitorSink = PcmMonitorBlockSink { _, _ -> true },
            clock = PcmMixerClock { System.nanoTime() / 1_000L },
            onWorkerFailure = { failure.countDown() }
        )

        assertTrue(worker.start())
        assertTrue(failure.await(2, TimeUnit.SECONDS))
        assertTrue(worker.awaitStopped(2_000L))
        assertFalse(worker.isRunning)
    }
}
