package com.iamahsana.ossstudio.stream.rtmp

import android.content.Context
import android.os.Looper
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.library.multiple.MultiStream
import com.pedro.library.multiple.MultiType
import com.pedro.library.view.GlStreamInterface
import java.util.UUID


internal fun reduceMultiRtmpState(
    destinationStates: List<MultiRtmpStreamSession.DestinationState>
): MultiRtmpStreamSession.State? {
    if (destinationStates.isEmpty()) return null
    return when {
        destinationStates.any { it == MultiRtmpStreamSession.DestinationState.CONNECTED } ->
            MultiRtmpStreamSession.State.LIVE
        destinationStates.any { it == MultiRtmpStreamSession.DestinationState.CONNECTING } ->
            MultiRtmpStreamSession.State.CONNECTING
        destinationStates.all {
            it in setOf(
                MultiRtmpStreamSession.DestinationState.FAILED,
                MultiRtmpStreamSession.DestinationState.AUTH_ERROR,
                MultiRtmpStreamSession.DestinationState.DISCONNECTED
            )
        } -> if (destinationStates.any {
                it == MultiRtmpStreamSession.DestinationState.FAILED ||
                    it == MultiRtmpStreamSession.DestinationState.AUTH_ERROR
            }) MultiRtmpStreamSession.State.FAILED else MultiRtmpStreamSession.State.STOPPED
        else -> null
    }
}

/**
 * One hardware encoder feeding independent RTMP destinations through RootEncoder MultiStream.
 * A failure on one destination changes only that destination's state.
 */
class MultiRtmpStreamSession(
    context: Context,
    profiles: List<RtmpDestinationProfile>,
    audioSource: AudioSource,
    private val listener: Listener? = null
) {
    enum class State { NEW, PREPARING, PREPARED, CONNECTING, LIVE, STOPPING, STOPPED, FAILED, RELEASED }
    enum class DestinationState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, FAILED, AUTH_ERROR }

    data class DestinationSnapshot(
        val id: String,
        val name: String,
        val state: DestinationState,
        val detail: String? = null
    )

    data class Config(
        val width: Int = 720,
        val height: Int = 1280,
        val fps: Int = 30,
        val videoBitrate: Int = 2_500_000,
        val audioSampleRate: Int = 48_000,
        val audioStereo: Boolean = true,
        val audioBitrate: Int = 64_000
    )

    interface Listener {
        fun onStateChanged(state: State, destinations: List<DestinationSnapshot>)
        fun onDestinationChanged(destination: DestinationSnapshot)
    }

    private val appContext = context.applicationContext
    private val enabledProfiles = profiles.filter { it.enabled }
    private val operationId = UUID.randomUUID().toString()
    private val stateLock = Any()
    private val destinationStates = enabledProfiles.map {
        DestinationSnapshot(it.id, it.name, DestinationState.IDLE)
    }.toMutableList()
    private val checkers = Array<ConnectChecker>(enabledProfiles.size) { index ->
        object : ConnectChecker {
            override fun onConnectionStarted(url: String) = updateDestination(index, DestinationState.CONNECTING)
            override fun onConnectionSuccess() = updateDestination(index, DestinationState.CONNECTED)
            override fun onConnectionFailed(reason: String) =
                updateDestination(index, DestinationState.FAILED, "Koneksi gagal")
            override fun onDisconnect() = updateDestination(index, DestinationState.DISCONNECTED)
            override fun onAuthError() = updateDestination(index, DestinationState.AUTH_ERROR, "Autentikasi ditolak")
            override fun onAuthSuccess() = log(OssLogLevel.INFO, "destination_auth_success", "RTMP destination authenticated", enabledProfiles[index].id)
        }
    }

    private val stream = MultiStream(
        appContext,
        checkers,
        null,
        null,
        null,
        NoVideoSource(),
        audioSource
    )

    @Volatile
    var state: State = State.NEW
        private set

    val snapshots: List<DestinationSnapshot>
        get() = synchronized(destinationStates) { destinationStates.toList() }

    val connectedCount: Int
        get() = snapshots.count { it.state == DestinationState.CONNECTED }

    val enabledDestinationCount: Int
        get() = enabledProfiles.size

    init {
        require(enabledProfiles.isNotEmpty()) { "At least one RTMP destination must be enabled" }
        require(enabledProfiles.map { it.id }.distinct().size == enabledProfiles.size) {
            "RTMP destination IDs must be unique"
        }
    }

    fun prepare(config: Config = Config()): Boolean {
        checkMainThread()
        check(state == State.NEW) { "Multi RTMP session cannot prepare from state $state" }
        state = State.PREPARING
        notifyState()
        return try {
            val videoReady = stream.prepareVideo(config.width, config.height, config.videoBitrate, config.fps)
            if (!videoReady) return failPrepare("Video encoder preparation failed")
            val audioReady = stream.prepareAudio(config.audioSampleRate, config.audioStereo, config.audioBitrate)
            if (!audioReady) return failPrepare("Audio encoder preparation failed")
            state = State.PREPARED
            notifyState()
            true
        } catch (error: Exception) {
            failPrepare(error.message ?: "Encoder preparation failed")
        }
    }

    fun glInterface(): GlStreamInterface {
        check(state == State.PREPARED || state == State.CONNECTING || state == State.LIVE) {
            "Multi RTMP session must be prepared before accessing GL"
        }
        return stream.getGlInterface()
    }

    /** Attempts every enabled destination; synchronous failure on one never skips the others. */
    fun startAll(): Int {
        checkMainThread()
        check(state == State.PREPARED) { "Multi RTMP session must be prepared before start" }
        state = State.CONNECTING
        notifyState()
        var attempted = 0
        enabledProfiles.forEachIndexed { index, profile ->
            runCatching {
                stream.startStream(MultiType.RTMP, index, profile.endpoint())
                attempted++
            }.onFailure {
                updateDestination(index, DestinationState.FAILED, "Tidak dapat memulai koneksi")
            }
        }
        if (attempted == 0) {
            state = State.FAILED
            notifyState()
        }
        return attempted
    }

    /** Disconnect all destination clients, then release the shared encoder once. */
    fun stopAndRelease() {
        checkMainThread()
        if (state == State.RELEASED || state == State.STOPPED) return
        state = State.STOPPING
        notifyState()
        enabledProfiles.indices.forEach { index ->
            runCatching { stream.stopStream(MultiType.RTMP, index) }
                .onFailure { updateDestination(index, DestinationState.FAILED, "Gagal memutus tujuan") }
        }
        val releaseResult = runCatching { stream.release() }
        releaseResult.onSuccess {
            state = State.STOPPED
            updateAllDisconnected()
        }.onFailure {
            state = State.FAILED
            log(OssLogLevel.ERROR, "multi_stream_release_failed", "Shared RTMP encoder release failed", error = it)
        }
        notifyState()
        releaseResult.exceptionOrNull()?.let {
            throw IllegalStateException("Shared RTMP encoder cleanup is incomplete", it)
        }
    }

    fun release() {
        checkMainThread()
        if (state == State.RELEASED) return
        if (state != State.STOPPED) stopAndRelease()
        check(state == State.STOPPED) { "Multi RTMP cleanup did not complete" }
        state = State.RELEASED
        notifyState()
    }

    private fun failPrepare(detail: String): Boolean {
        state = State.FAILED
        runCatching { stream.release() }
        log(OssLogLevel.ERROR, "multi_stream_prepare_failed", detail, errorCode = "MULTI_RTMP_PREPARE_FAILED")
        notifyState()
        return false
    }

    private fun updateDestination(index: Int, next: DestinationState, detail: String? = null) {
        val snapshot: DestinationSnapshot
        synchronized(stateLock) {
            snapshot = synchronized(destinationStates) {
                destinationStates[index] = destinationStates[index].copy(state = next, detail = detail)
                destinationStates[index]
            }
            if (state !in setOf(State.STOPPING, State.STOPPED, State.RELEASED)) {
                reduceMultiRtmpState(snapshots.map { it.state })?.let { state = it }
            }
        }
        runCatching { listener?.onDestinationChanged(snapshot) }
        notifyState()
        if (next == DestinationState.FAILED || next == DestinationState.AUTH_ERROR) {
            log(OssLogLevel.ERROR, "destination_connection_failed", detail ?: "RTMP destination failed", snapshot.id, "RTMP_DESTINATION_FAILED")
        }
    }

    private fun updateAllDisconnected() {
        synchronized(destinationStates) {
            destinationStates.indices.forEach { index ->
                destinationStates[index] = destinationStates[index].copy(state = DestinationState.DISCONNECTED)
            }
        }
    }

    private fun notifyState() {
        val snapshot = snapshots
        runCatching { listener?.onStateChanged(state, snapshot) }
    }

    private fun log(
        level: OssLogLevel,
        event: String,
        message: String,
        destinationId: String? = null,
        errorCode: String? = null,
        error: Throwable? = null
    ) {
        OssLogger.log(
            level = level,
            subsystem = "OSS/Stream/MultiRTMP",
            component = "MultiRtmpStreamSession",
            event = event,
            message = message,
            errorCode = errorCode,
            context = OssLogContext(operationId = operationId, destinationId = destinationId),
            throwable = error
        )
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "MultiRtmpStreamSession lifecycle must run on the main thread"
        }
    }
}
