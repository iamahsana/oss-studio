package com.iamahsana.ossstudio.audio

import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Non-blocking destination for one already-mixed LIVE PCM block. */
fun interface PcmLiveBlockSink {
    fun submitBlock(pcm: ByteArray, timestampMicros: Long): Int
}

/** Non-blocking MONITOR queue; actual device writes happen on its own thread. */
fun interface PcmMonitorBlockSink {
    fun offerBlock(pcm: ByteArray, timestampMicros: Long): Boolean
}

/** Clock must use the same monotonic timebase as capture frame timestamps. */
fun interface PcmMixerClock {
    fun nowMicros(): Long
}

/**
 * Dedicated 10 ms mixer worker.
 *
 * Capture callbacks only publish to SPSC queues. This worker aligns and mixes
 * fixed blocks, sends LIVE PCM to RootEncoder and enqueues MONITOR PCM without
 * performing AudioTrack writes on the timing-critical mixer thread.
 *
 * The caller must prepare/activate the LIVE output and start the MONITOR sink
 * before start(). Stop is split into requestStop()/awaitStopped() so a UI
 * thread can signal shutdown without joining a real-time worker.
 */
class PcmMixerWorker(
    private val engine: PcmMixerEngine,
    private val liveSink: PcmLiveBlockSink,
    private val monitorSink: PcmMonitorBlockSink,
    private val clock: PcmMixerClock,
    private val sampleRateHz: Int = 48_000,
    private val lookAheadMicros: Long = 40_000L,
    private val onWorkerFailure: ((Throwable) -> Unit)? = null
) {
    init {
        require(sampleRateHz > 0)
        require(lookAheadMicros >= 0)
    }

    private val running = AtomicBoolean(false)
    private val skippedBlocks = AtomicLong(0L)
    private val liveRejectedBlocks = AtomicLong(0L)
    private val monitorRejectedBlocks = AtomicLong(0L)
    private val operationId = UUID.randomUUID().toString()
    private val blockDurationMicros =
        engine.framesPerBlock.toLong() * MICROS_PER_SECOND / sampleRateHz

    @Volatile
    private var workerThread: Thread? = null

    init {
        require(blockDurationMicros > 0L) { "Mixer block duration must be at least one microsecond" }
    }

    val isRunning: Boolean
        get() = running.get()

    val overrunBlocks: Long
        get() = skippedBlocks.get()

    val liveRejectedBlocksCount: Long
        get() = liveRejectedBlocks.get()

    val monitorRejectedBlocksCount: Long
        get() = monitorRejectedBlocks.get()

    @Synchronized
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val thread = Thread(::runLoop, "oss-pcm-mixer").apply { isDaemon = true }
        workerThread = thread
        thread.start()
        log(OssLogLevel.INFO, "mixer_worker_started", "PCM mixer worker started")
        return true
    }

    /** Safe to call from the main thread; does not wait for worker termination. */
    fun requestStop() {
        running.set(false)
        workerThread?.interrupt()
    }

    /** Await from a background lifecycle executor, never from the UI thread. */
    fun awaitStopped(timeoutMillis: Long = 2_000L): Boolean {
        require(timeoutMillis >= 0L)
        val thread = workerThread ?: return true
        if (thread === Thread.currentThread()) return false
        thread.join(timeoutMillis)
        return !thread.isAlive
    }

    private fun runLoop() {
        var blockStartMicros = clock.nowMicros() - lookAheadMicros
        try {
            while (running.get()) {
                engine.processBlock(blockStartMicros)

                val liveBytes = liveSink.submitBlock(engine.liveOutput, blockStartMicros)
                if (liveBytes < engine.liveOutput.size) {
                    liveRejectedBlocks.incrementAndGet()
                }
                if (!monitorSink.offerBlock(engine.monitorOutput, blockStartMicros)) {
                    monitorRejectedBlocks.incrementAndGet()
                }

                blockStartMicros += blockDurationMicros
                val deadlineMicros = blockStartMicros + lookAheadMicros
                val nowMicros = clock.nowMicros()
                if (nowMicros >= deadlineMicros) {
                    // Never burst old blocks to catch up. Jump to the newest
                    // block that still preserves the configured look-ahead.
                    val lateBy = nowMicros - deadlineMicros
                    val skip = lateBy / blockDurationMicros + 1L
                    skippedBlocks.addAndGet(skip)
                    blockStartMicros += skip * blockDurationMicros
                    continue
                }

                LockSupport.parkNanos((deadlineMicros - nowMicros) * NANOS_PER_MICRO)
                if (Thread.interrupted() && !running.get()) break
            }
        } catch (error: Exception) {
            log(
                OssLogLevel.ERROR,
                "mixer_worker_failed",
                "PCM mixer worker stopped after an unexpected processing error",
                "AUDIO_MIXER_WORKER_FAILED",
                error
            )
            runCatching { onWorkerFailure?.invoke(error) }
        } finally {
            running.set(false)
            workerThread = null
            log(OssLogLevel.INFO, "mixer_worker_stopped", "PCM mixer worker stopped")
        }
    }

    private fun log(
        level: OssLogLevel,
        event: String,
        message: String,
        errorCode: String? = null,
        error: Throwable? = null
    ) {
        OssLogger.log(
            level = level,
            subsystem = "OSS/Audio/Core",
            component = "PcmMixerWorker",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(operationId = operationId),
            throwable = error
        )
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val NANOS_PER_MICRO = 1_000L
    }
}
