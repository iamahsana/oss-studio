package com.iamahsana.ossstudio.audio

import android.media.AudioDeviceInfo
import android.media.MediaProjection
import com.iamahsana.ossstudio.logging.OssLogContext
import com.iamahsana.ossstudio.logging.OssLogLevel
import com.iamahsana.ossstudio.logging.OssLogger
import com.pedro.encoder.input.sources.audio.MixAudioSource
import java.util.UUID

/**
 * OSS control surface for RootEncoder's proven microphone + playback mixer.
 *
 * RootEncoder owns the AudioSource lifecycle after it is passed to RtmpStream.
 * Do not call release() here while a stream still owns the source.
 *
 * This upstream source mixes microphone and Android playback into one encoded
 * track. It does NOT provide independent per-app capture or independent
 * monitor/live buses; those remain explicit future mixer requirements.
 */
class RootEncoderAudioMixer(
    mediaProjection: MediaProjection,
    private val operationId: String = UUID.randomUUID().toString()
) {
    val source: MixAudioSource = MixAudioSource(mediaProjection)

    @Volatile
    var microphoneVolume: Float = DEFAULT_MICROPHONE_VOLUME
        private set

    @Volatile
    var internalVolume: Float = DEFAULT_INTERNAL_VOLUME
        private set

    fun setMicrophoneVolume(value: Float) {
        require(value.isFinite() && value >= 0f) { "Microphone volume must be finite and non-negative" }
        source.microphoneVolume = value
        microphoneVolume = value
        log("microphone_volume_changed", "Microphone gain updated")
    }

    fun setInternalVolume(value: Float) {
        require(value.isFinite() && value >= 0f) { "Internal audio volume must be finite and non-negative" }
        source.internalVolume = value
        internalVolume = value
        log("internal_volume_changed", "Internal audio gain updated")
    }

    fun muteAll() {
        source.mute()
        log("all_channels_muted", "Combined audio source muted")
    }

    fun unmuteAll() {
        source.unMute()
        log("all_channels_unmuted", "Combined audio source unmuted")
    }

    fun setPreferredInput(device: AudioDeviceInfo?): Boolean {
        val selected = source.setPreferredDevice(device)
        log(
            event = "preferred_input_changed",
            message = if (selected) "Preferred audio input accepted" else "Preferred audio input rejected",
            level = if (selected) OssLogLevel.INFO else OssLogLevel.WARN
        )
        return selected
    }

    fun setProcessing(echoCanceler: Boolean, noiseSuppressor: Boolean) {
        source.echoCanceler = echoCanceler
        source.noiseSuppressor = noiseSuppressor
        log("audio_processing_changed", "Echo cancellation and noise suppression settings updated")
    }

    val isRunning: Boolean
        get() = source.isRunning()

    private fun log(
        event: String,
        message: String,
        level: OssLogLevel = OssLogLevel.INFO
    ) {
        OssLogger.log(
            level = level,
            subsystem = "OSS/Audio/Core",
            component = "RootEncoderAudioMixer",
            event = event,
            message = message,
            context = OssLogContext(operationId = operationId)
        )
    }

    companion object {
        // RootEncoder's own documentation recommends a higher mic gain because
        // Android playback is commonly louder. This is a starting point only.
        const val DEFAULT_MICROPHONE_VOLUME = 2f
        const val DEFAULT_INTERNAL_VOLUME = 1f
    }
}
