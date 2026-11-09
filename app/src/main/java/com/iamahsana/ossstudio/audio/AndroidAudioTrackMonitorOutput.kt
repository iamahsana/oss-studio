package com.iamahsana.ossstudio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Android MONITOR bus output backed by AudioTrack.
 *
 * Mixer-to-monitor transfer is a bounded SPSC queue. The AudioTrack writer has
 * its own thread and uses WRITE_NON_BLOCKING, so device backpressure cannot
 * stall the LIVE mixer worker. Create one instance per monitor session.
 */
class AndroidAudioTrackMonitorOutput(
    private val sampleRateHz: Int = 48_000,
    queueCapacity: Int = 8,
    private val maxBlockBytes: Int = 8192
) : PcmMonitorBlockSink {

    init {
        require(sampleRateHz > 0)
        require(queueCapacity > 0)
        require(maxBlockBytes >= 4)
    }

    private val queue = SpscPcmFrameQueue(queueCapacity, maxBlockBytes)
    private val running = AtomicBoolean(false)
    private val droppedBlocks = AtomicLong(0L)
    private val writeErrors = AtomicLong(0L)
    private val operationId = UUID.randomUUID().toString()

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var writerThread: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    val droppedBlockCount: Long
        get() = droppedBlocks.get()

    val writeErrorCount: Long
        get() = writeErrors.get()

    /**
     * Prepare and start the monitor device. This does not request permissions.
     * Android audio routing/device selection is left to the system for now.
     */
    @Synchronized
    fun start(): Boolean {
        if (running.get() || audioTrack != null) return false
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            log(OssLogLevel.ERROR, "monitor_track_min_buffer_failed", "Android returned an invalid AudioTrack minimum buffer", "AUDIO_MONITOR_BUFFER_UNAVAILABLE")
            return false
        }

        val blockBytes = DEFAULT_BLOCK_FRAMES * BYTES_PER_FRAME
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, blockBytes * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrElse {
            log(OssLogLevel.ERROR, "monitor_track_create_failed", "Unable to create Android monitor AudioTrack", "AUDIO_MONITOR_CREATE_FAILED", it)
            return false
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            log(OssLogLevel.ERROR, "monitor_track_uninitialized", "Android monitor AudioTrack was not initialized", "AUDIO_MONITOR_NOT_INITIALIZED")
            return false
        }

        return try {
            track.play()
            audioTrack = track
            running.set(true)
            val thread = Thread({ writeLoop(track) }, "oss-audio-monitor").apply { isDaemon = true }
            writerThread = thread
            thread.start()
            log(OssLogLevel.INFO, "monitor_track_started", "Android MONITOR audio output started")
            true
        } catch (error: Exception) {
            running.set(false)
            audioTrack = null
            runCatching { track.release() }
            log(OssLogLevel.ERROR, "monitor_track_start_failed", "Unable to start Android monitor output", "AUDIO_MONITOR_START_FAILED", error)
            false
        }
    }

    override fun offerBlock(pcm: ByteArray, timestampMicros: Long): Boolean {
        if (!running.get()) {
            droppedBlocks.incrementAndGet()
            return false
        }
        val accepted = queue.offer(pcm, 0, pcm.size, timestampMicros)
        if (!accepted) droppedBlocks.incrementAndGet()
        return accepted
    }

    /** Signal only; call awaitStopped from a background lifecycle executor. */
    fun requestStop() {
        running.set(false)
        writerThread?.interrupt()
    }

    @Synchronized
    fun awaitStopped(timeoutMillis: Long = 1_000L): Boolean {
        require(timeoutMillis >= 0L)
        val thread = writerThread
        if (thread != null) {
            if (thread === Thread.currentThread()) return false
            thread.join(timeoutMillis)
            if (thread.isAlive) return false
        }
        val track = audioTrack
        audioTrack = null
        writerThread = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        log(OssLogLevel.INFO, "monitor_track_stopped", "Android MONITOR audio output stopped")
        return true
    }

    private fun writeLoop(track: AudioTrack) {
        val frameBuffer = ByteArray(maxBlockBytes)
        val frameInfo = SpscPcmFrameQueue.FrameInfo()
        var pendingSize = 0
        var pendingOffset = 0

        try {
            while (running.get()) {
                if (pendingOffset >= pendingSize) {
                    if (!queue.poll(frameBuffer, frameInfo)) {
                        LockSupport.parkNanos(IDLE_PARK_NANOS)
                        if (Thread.interrupted() && !running.get()) break
                        continue
                    }
                    pendingSize = frameInfo.size
                    pendingOffset = 0
                    if (pendingSize == 0 || pendingSize % BYTES_PER_FRAME != 0) {
                        writeErrors.incrementAndGet()
                        pendingOffset = pendingSize
                        continue
                    }
                }

                val written = track.write(
                    frameBuffer,
                    pendingOffset,
                    pendingSize - pendingOffset,
                    AudioTrack.WRITE_NON_BLOCKING
                )
                when {
                    written > 0 -> pendingOffset += written
                    written == 0 -> LockSupport.parkNanos(IDLE_PARK_NANOS)
                    else -> {
                        writeErrors.incrementAndGet()
                        log(OssLogLevel.ERROR, "monitor_track_write_failed", "Android AudioTrack rejected monitor PCM", "AUDIO_MONITOR_WRITE_FAILED")
                        break
                    }
                }
                if (Thread.interrupted() && !running.get()) break
            }
        } catch (error: Exception) {
            writeErrors.incrementAndGet()
            log(OssLogLevel.ERROR, "monitor_track_writer_exception", "Android monitor writer stopped unexpectedly", "AUDIO_MONITOR_WRITER_FAILED", error)
        } finally {
            running.set(false)
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
            subsystem = "OSS/Audio/Monitor",
            component = "AndroidAudioTrackMonitorOutput",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(operationId = operationId),
            throwable = error
        )
    }

    private companion object {
        const val DEFAULT_BLOCK_FRAMES = 480
        const val BYTES_PER_FRAME = 4
        const val IDLE_PARK_NANOS = 500_000L
    }
}
