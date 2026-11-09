package com.iamahsana.ossstudio.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.MediaProjectionHandler
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import java.util.UUID

/**
 * Owns independent RootEncoder microphone and Android playback-capture sources.
 *
 * Each source feeds its own SPSC queue. This class deliberately does not use
 * RootEncoder MixAudioSource: OSS must retain independent channel routing.
 * The session is not yet wired into LIVE; it is the capture/lifecycle adapter
 * that the mixer worker will consume once monitor/output integration is ready.
 */
class RootEncoderAudioCaptureSession(
    context: Context,
    private val listener: ((Snapshot) -> Unit)? = null,
    private val sourceFactory: SourceFactory = RootEncoderSourceFactory
) {
    enum class SourceState { STOPPED, RUNNING, UNAVAILABLE, FAILED }

    data class SourceStatus(
        val state: SourceState,
        val errorCode: String? = null
    )

    data class Snapshot(
        val microphone: SourceStatus,
        val internalAudio: SourceStatus
    ) {
        val hasAnySource: Boolean
            get() = microphone.state == SourceState.RUNNING ||
                internalAudio.state == SourceState.RUNNING
    }

    /** Small seam for deterministic lifecycle tests without Android audio I/O. */
    interface CaptureSource {
        fun initialize(sampleRateHz: Int, stereo: Boolean): Boolean
        fun start(sink: GetMicrophoneData)
        fun stop()
        fun release()
    }

    interface SourceFactory {
        fun createMicrophone(): CaptureSource
        fun createInternal(
            projection: MediaProjection,
            callback: MediaProjection.Callback
        ): CaptureSource
    }

    private val appContext = context.applicationContext
    private val operationId = UUID.randomUUID().toString()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    val microphoneQueue = SpscPcmFrameQueue()
    val internalAudioQueue = SpscPcmFrameQueue()
    private val microphoneSink = RootEncoderPcmCaptureSink(microphoneQueue)
    private val internalSink = RootEncoderPcmCaptureSink(internalAudioQueue)

    private var microphoneSource: CaptureSource? = null
    private var internalSource: CaptureSource? = null
    private var ownedProjection: MediaProjection? = null
    private var stopped = true

    @Volatile
    private var currentSnapshot = Snapshot(
        SourceStatus(SourceState.STOPPED),
        SourceStatus(SourceState.STOPPED)
    )

    val snapshot: Snapshot
        get() = currentSnapshot

    /**
     * Automatically starts every core source currently available.
     * Missing permissions/policy restrictions are represented per channel;
     * one source failing never prevents the other source from starting.
     */
    fun start(projection: MediaProjection?): Snapshot {
        synchronized(lock) {
            check(stopped) { "Audio capture session is already running" }
            stopped = false

            val micStatus = startMicrophone()
            val internalStatus = startInternal(projection)

            publish(micStatus, internalStatus)
            return currentSnapshot
        }
    }

    /** Stop accepting callback frames before stopping either RootEncoder source. */
    fun stop(): Snapshot {
        synchronized(lock) {
            if (stopped) return currentSnapshot
            stopped = true

            microphoneSink.endCapture()
            internalSink.endCapture()

            microphoneSource?.let { source ->
                runCatching { source.stop() }
                    .onFailure { log(OssLogLevel.ERROR, "microphone_stop_failed", "Microphone source stop failed", "AUDIO_MIC_STOP_FAILED", it) }
                runCatching { source.release() }
                    .onFailure { log(OssLogLevel.WARN, "microphone_release_failed", "Microphone source release failed", "AUDIO_MIC_RELEASE_FAILED", it) }
            }
            microphoneSource = null

            internalSource?.let { source ->
                runCatching { source.stop() }
                    .onFailure { log(OssLogLevel.ERROR, "internal_stop_failed", "Internal audio source stop failed", "AUDIO_INTERNAL_STOP_FAILED", it) }
                runCatching { source.release() }
                    .onFailure { log(OssLogLevel.WARN, "internal_release_failed", "Internal audio source release failed", "AUDIO_INTERNAL_RELEASE_FAILED", it) }
            }
            internalSource = null
            releaseProjectionLease()

            publish(
                SourceStatus(SourceState.STOPPED),
                SourceStatus(SourceState.STOPPED)
            )
            log(OssLogLevel.INFO, "capture_session_stopped", "OSS audio capture sources stopped")
            return currentSnapshot
        }
    }

    private fun startMicrophone(): SourceStatus {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log(OssLogLevel.WARN, "microphone_permission_missing", "Microphone channel unavailable: permission not granted", "AUDIO_MIC_PERMISSION_REQUIRED")
            return SourceStatus(SourceState.UNAVAILABLE, "AUDIO_MIC_PERMISSION_REQUIRED")
        }

        val source = runCatching { sourceFactory.createMicrophone() }.getOrElse {
            log(OssLogLevel.ERROR, "microphone_create_failed", "Unable to create microphone source", "AUDIO_MIC_CREATE_FAILED", it)
            return SourceStatus(SourceState.FAILED, "AUDIO_MIC_CREATE_FAILED")
        }

        return try {
            check(source.initialize(SAMPLE_RATE_HZ, stereo = true)) {
                "RootEncoder microphone source initialization returned false"
            }
            microphoneSink.beginCapture()
            source.start(microphoneSink)
            microphoneSource = source
            log(OssLogLevel.INFO, "microphone_started", "Independent microphone PCM capture started")
            SourceStatus(SourceState.RUNNING)
        } catch (error: Exception) {
            microphoneSink.endCapture()
            runCatching { source.stop() }
            runCatching { source.release() }
            log(OssLogLevel.ERROR, "microphone_start_failed", "Microphone channel failed to start", "AUDIO_MIC_START_FAILED", error)
            SourceStatus(SourceState.FAILED, "AUDIO_MIC_START_FAILED")
        }
    }

    private fun startInternal(projection: MediaProjection?): SourceStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log(OssLogLevel.WARN, "internal_api_unsupported", "Internal audio capture requires Android 10 or newer", "AUDIO_INTERNAL_API_UNSUPPORTED")
            return SourceStatus(SourceState.UNAVAILABLE, "AUDIO_INTERNAL_API_UNSUPPORTED")
        }
        if (projection == null) {
            log(OssLogLevel.WARN, "internal_projection_missing", "Internal audio channel unavailable: MediaProjection is not active", "AUDIO_INTERNAL_PROJECTION_REQUIRED")
            return SourceStatus(SourceState.UNAVAILABLE, "AUDIO_INTERNAL_PROJECTION_REQUIRED")
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log(OssLogLevel.WARN, "internal_record_permission_missing", "Internal playback capture requires RECORD_AUDIO permission", "AUDIO_INTERNAL_RECORD_PERMISSION_REQUIRED")
            return SourceStatus(SourceState.UNAVAILABLE, "AUDIO_INTERNAL_RECORD_PERMISSION_REQUIRED")
        }
        if (!InternalProjectionLease.acquire(projection)) {
            log(OssLogLevel.ERROR, "internal_projection_busy", "Internal audio capture projection is already owned by another session", "AUDIO_INTERNAL_PROJECTION_BUSY")
            return SourceStatus(SourceState.FAILED, "AUDIO_INTERNAL_PROJECTION_BUSY")
        }
        ownedProjection = projection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                internalSink.endCapture()
                log(OssLogLevel.WARN, "internal_projection_stopped", "Android stopped the internal audio capture projection", "AUDIO_INTERNAL_PROJECTION_STOPPED")
                // Never stop/unregister InternalAudioSource from its own
                // projection callback thread. Defer teardown to a fresh worker.
                Thread({ releaseInternalAfterProjectionStop() }, "oss-internal-audio-cleanup").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        val source = runCatching { sourceFactory.createInternal(projection, callback) }.getOrElse {
            releaseProjectionLease()
            log(OssLogLevel.ERROR, "internal_create_failed", "Unable to create internal audio source", "AUDIO_INTERNAL_CREATE_FAILED", it)
            return SourceStatus(SourceState.FAILED, "AUDIO_INTERNAL_CREATE_FAILED")
        }

        return try {
            check(source.initialize(SAMPLE_RATE_HZ, stereo = true)) {
                "RootEncoder internal audio source initialization returned false"
            }
            internalSink.beginCapture()
            source.start(internalSink)
            internalSource = source
            log(OssLogLevel.INFO, "internal_audio_started", "Independent Android playback capture started")
            SourceStatus(SourceState.RUNNING)
        } catch (error: Exception) {
            internalSink.endCapture()
            runCatching { source.stop() }
            runCatching { source.release() }
            releaseProjectionLease()
            log(OssLogLevel.ERROR, "internal_start_failed", "Internal audio channel failed to start; microphone remains independent", "AUDIO_INTERNAL_START_FAILED", error)
            SourceStatus(SourceState.FAILED, "AUDIO_INTERNAL_START_FAILED")
        }
    }

    private fun releaseInternalAfterProjectionStop() {
        synchronized(lock) {
            if (stopped) {
                releaseProjectionLease()
                return
            }
            internalSink.endCapture()
            internalSource?.let { source ->
                runCatching { source.stop() }
                    .onFailure { log(OssLogLevel.WARN, "internal_projection_cleanup_failed", "Internal audio cleanup after projection stop failed", "AUDIO_INTERNAL_CLEANUP_FAILED", it) }
                runCatching { source.release() }
            }
            internalSource = null
            releaseProjectionLease()
            publish(
                currentSnapshot.microphone,
                SourceStatus(SourceState.UNAVAILABLE, "AUDIO_INTERNAL_PROJECTION_STOPPED")
            )
        }
    }

    private fun releaseProjectionLease() {
        val projection = ownedProjection ?: return
        InternalProjectionLease.release(projection)
        ownedProjection = null
    }

    private fun publish(microphone: SourceStatus, internalAudio: SourceStatus) {
        val next = Snapshot(microphone, internalAudio)
        currentSnapshot = next
        mainHandler.post { runCatching { listener?.invoke(next) } }
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
            component = "RootEncoderAudioCaptureSession",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(operationId = operationId),
            throwable = error
        )
    }

    companion object {
        const val SAMPLE_RATE_HZ = 48_000
    }
}

/**
 * RootEncoder keeps the internal MediaProjection in a process-wide singleton.
 * OSS serializes ownership and clears only the exact projection it acquired.
 */
internal object InternalProjectionLease {
    private var owner: MediaProjection? = null

    @Synchronized
    fun acquire(projection: MediaProjection): Boolean {
        if (owner != null) return false
        val current = MediaProjectionHandler.mediaProjection
        if (current != null && current !== projection) return false
        owner = projection
        MediaProjectionHandler.mediaProjection = projection
        return true
    }

    @Synchronized
    fun release(projection: MediaProjection) {
        if (owner !== projection) return
        if (MediaProjectionHandler.mediaProjection === projection) {
            MediaProjectionHandler.mediaProjection = null
        }
        owner = null
    }
}

private object RootEncoderSourceFactory : RootEncoderAudioCaptureSession.SourceFactory {
    override fun createMicrophone(): RootEncoderAudioCaptureSession.CaptureSource {
        val source = MicrophoneSource()
        return RootEncoderCaptureSourceAdapter(source)
    }

    override fun createInternal(
        projection: MediaProjection,
        callback: MediaProjection.Callback
    ): RootEncoderAudioCaptureSession.CaptureSource {
        val source = InternalAudioSource(projection, callback)
        return RootEncoderCaptureSourceAdapter(source)
    }
}

private class RootEncoderCaptureSourceAdapter(
    private val source: AudioSource
) : RootEncoderAudioCaptureSession.CaptureSource {
    override fun initialize(sampleRateHz: Int, stereo: Boolean): Boolean {
        // RootEncoder's MicrophoneSource.start() and InternalAudioSource.start()
        // create their AudioRecord in the start path. Calling AudioSource.init()
        // first creates an extra AudioRecord (and InternalAudioSource creates a
        // microphone AudioRecord instead of playback capture). Set the negotiated
        // format directly so each source allocates only its intended recorder.
        source.sampleRate = sampleRateHz
        source.isStereo = stereo
        source.echoCanceler = false
        source.noiseSuppressor = false
        return true
    }

    override fun start(sink: GetMicrophoneData) = source.start(sink)
    override fun stop() = source.stop()
    override fun release() = source.release()
}
