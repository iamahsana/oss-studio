package com.iamahsana.ossstudio.audio

import android.content.Context
import android.media.projection.MediaProjection
import com.pedro.encoder.input.sources.audio.BufferAudioSource
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one complete OSS audio graph for a LIVE session.
 *
 * start() and stopAsync() must be invoked from a background executor. The
 * caller must prepare the exact BufferAudioSource injected into RtmpStream
 * before start(), and start the RTMP stream before starting this graph.
 *
 * Teardown is deliberately asynchronous: the mixer worker is joined before
 * capture sources are stopped/released, then MONITOR is drained and released.
 * The completion callback runs on the cleanup thread; Android callers must
 * marshal it to the main thread before continuing RTMP/MediaProjection teardown.
 */
class OssAudioMixerSession(
    context: Context,
    bufferAudioSource: BufferAudioSource,
    private val onCaptureStatus: ((RootEncoderAudioCaptureSession.Snapshot) -> Unit)? = null
) {
    enum class State { NEW, STARTING, RUNNING, STOPPING, STOPPED, FAILED }

    data class StopResult(
        val completed: Boolean,
        val mixerStopped: Boolean,
        val captureStopped: Boolean,
        val monitorStopped: Boolean
    )

    private val operationId = UUID.randomUUID().toString()
    private val capture = RootEncoderAudioCaptureSession(
        context = context.applicationContext,
        listener = onCaptureStatus
    )
    private val liveOutput = RootEncoderBufferAudioOutput(bufferAudioSource)
    private val monitorOutput = AndroidAudioTrackMonitorOutput()
    private val engine = PcmMixerEngine(
        sourceQueues = arrayOf(capture.microphoneQueue, capture.internalAudioQueue),
        initialConfigs = arrayOf(ChannelMixConfig(), ChannelMixConfig()),
        framesPerBlock = DEFAULT_BLOCK_FRAMES,
        sampleRateHz = RootEncoderAudioCaptureSession.SAMPLE_RATE_HZ
    )
    private val worker = PcmMixerWorker(
        engine = engine,
        liveSink = liveOutput,
        monitorSink = monitorOutput,
        clock = AndroidPcmMixerClock,
        sampleRateHz = RootEncoderAudioCaptureSession.SAMPLE_RATE_HZ
    )
    private val cleanupInProgress = AtomicBoolean(false)

    @Volatile
    var state: State = State.NEW
        private set

    val captureSnapshot: RootEncoderAudioCaptureSession.Snapshot
        get() = capture.snapshot

    val livePeak: Float
        get() = engine.livePeak

    val liveRms: Float
        get() = engine.liveRms

    val monitorPeak: Float
        get() = engine.monitorPeak

    val monitorRms: Float
        get() = engine.monitorRms

    val liveLimiterGain: Float
        get() = engine.liveLimiterGain

    val monitorLimiterGain: Float
        get() = engine.monitorLimiterGain

    val mixerOverrunBlocks: Long
        get() = worker.overrunBlocks

    val monitorDroppedBlocks: Long
        get() = monitorOutput.droppedBlockCount

    /**
     * Starts all available core capture channels. A missing source is reported
     * independently; LIVE audio is ready if at least one channel is running.
     */
    @Synchronized
    fun start(projection: MediaProjection?): Boolean {
        check(state == State.NEW || state == State.STOPPED) {
            "Audio mixer session cannot start from state $state"
        }
        state = State.STARTING

        if (!liveOutput.activateAfterEncoderPrepare()) {
            state = State.FAILED
            log(
                OssLogLevel.ERROR,
                "live_output_not_prepared",
                "BufferAudioSource is not prepared; audio graph cannot start",
                "AUDIO_LIVE_OUTPUT_NOT_PREPARED"
            )
            return false
        }

        return try {
            val sources = capture.start(projection)
            if (!sources.hasAnySource) {
                liveOutput.deactivate()
                state = State.FAILED
                log(
                    OssLogLevel.ERROR,
                    "no_audio_source_available",
                    "Neither microphone nor internal playback capture is available",
                    "AUDIO_NO_SOURCE_AVAILABLE"
                )
                return false
            }

            if (!monitorOutput.start()) {
                log(
                    OssLogLevel.WARN,
                    "monitor_output_unavailable",
                    "MONITOR output is unavailable; LIVE audio remains independent",
                    "AUDIO_MONITOR_UNAVAILABLE"
                )
            }

            check(worker.start()) { "PCM mixer worker could not start" }
            state = State.RUNNING
            log(
                OssLogLevel.INFO,
                "audio_graph_started",
                "OSS independent capture, mixer, LIVE and MONITOR graph started"
            )
            true
        } catch (error: Exception) {
            liveOutput.deactivate()
            state = State.FAILED
            log(
                OssLogLevel.ERROR,
                "audio_graph_start_failed",
                "OSS audio graph failed to start",
                "AUDIO_GRAPH_START_FAILED",
                error
            )
            false
        }
    }

    /** Apply one channel strip update without interrupting the audio worker. */
    fun updateChannelConfig(index: Int, config: ChannelMixConfig) {
        engine.updateChannelConfig(index, config)
    }

    fun setLiveMasterGain(gain: Float) {
        engine.liveMasterGain = gain
    }

    fun setMonitorMasterGain(gain: Float) {
        engine.monitorMasterGain = gain
    }

    /**
     * Stop in dependency order on a dedicated cleanup thread.
     * A failed mixer join keeps capture sources alive for a safe retry.
     */
    fun stopAsync(onComplete: (StopResult) -> Unit) {
        if (state == State.STOPPED) {
            onComplete(StopResult(true, true, true, true))
            return
        }
        if (!cleanupInProgress.compareAndSet(false, true)) return
        state = State.STOPPING
        worker.requestStop()

        Thread({
            var mixerStopped = false
            var captureStopped = false
            var monitorStopped = false
            try {
                mixerStopped = worker.awaitStopped(MIXER_STOP_TIMEOUT_MS)
                if (mixerStopped) {
                    liveOutput.deactivate()
                    val captureState = capture.stop()
                    captureStopped =
                        captureState.microphone.state == RootEncoderAudioCaptureSession.SourceState.STOPPED &&
                        captureState.internalAudio.state == RootEncoderAudioCaptureSession.SourceState.STOPPED

                    monitorOutput.requestStop()
                    monitorStopped = monitorOutput.awaitStopped(MONITOR_STOP_TIMEOUT_MS)
                } else {
                    log(
                        OssLogLevel.ERROR,
                        "mixer_stop_timeout",
                        "Mixer worker did not stop; capture and monitor ownership retained",
                        "AUDIO_MIXER_STOP_TIMEOUT"
                    )
                }
            } catch (error: Exception) {
                log(
                    OssLogLevel.ERROR,
                    "audio_graph_stop_failed",
                    "Audio graph teardown failed",
                    "AUDIO_GRAPH_STOP_FAILED",
                    error
                )
            } finally {
                val result = StopResult(
                    completed = mixerStopped && captureStopped && monitorStopped,
                    mixerStopped = mixerStopped,
                    captureStopped = captureStopped,
                    monitorStopped = monitorStopped
                )
                state = if (result.completed) State.STOPPED else State.FAILED
                cleanupInProgress.set(false)
                log(
                    if (result.completed) OssLogLevel.INFO else OssLogLevel.ERROR,
                    if (result.completed) "audio_graph_stopped" else "audio_graph_cleanup_pending",
                    if (result.completed) "OSS audio graph fully stopped" else "OSS audio graph cleanup remains incomplete",
                    if (result.completed) null else "AUDIO_GRAPH_CLEANUP_PENDING"
                )
                runCatching { onComplete(result) }
            }
        }, "oss-audio-graph-cleanup").apply {
            isDaemon = true
            start()
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
            component = "OssAudioMixerSession",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(operationId = operationId),
            throwable = error
        )
    }

    private companion object {
        const val DEFAULT_BLOCK_FRAMES = 480
        const val MIXER_STOP_TIMEOUT_MS = 2_000L
        const val MONITOR_STOP_TIMEOUT_MS = 1_000L
    }
}
