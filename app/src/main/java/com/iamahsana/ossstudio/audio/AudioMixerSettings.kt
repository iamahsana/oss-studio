package com.iamahsana.ossstudio.audio

import android.content.Context

data class AudioMixerSettings(
    val microphone: ChannelMixConfig = ChannelMixConfig(),
    val internalAudio: ChannelMixConfig = ChannelMixConfig(),
    val liveMasterGain: Float = 1f,
    val monitorMasterGain: Float = 1f
) {
    init {
        require(liveMasterGain in 0f..2f)
        require(monitorMasterGain in 0f..2f)
    }
}

/** Stores user mixer preferences only; no captured audio or stream credentials are stored here. */
object AudioMixerSettingsStore {
    private const val PREFERENCES = "oss_audio_mixer"
    private const val LIVE_MASTER = "live_master"
    private const val MONITOR_MASTER = "monitor_master"

    fun load(context: Context): AudioMixerSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return AudioMixerSettings(
            microphone = readChannel(prefs, "mic"),
            internalAudio = readChannel(prefs, "internal"),
            liveMasterGain = prefs.getFloat(LIVE_MASTER, 1f).coerceIn(0f, 2f),
            monitorMasterGain = prefs.getFloat(MONITOR_MASTER, 1f).coerceIn(0f, 2f)
        )
    }

    fun persist(context: Context, settings: AudioMixerSettings) {
        val editor = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
        writeChannel(editor, "mic", settings.microphone)
        writeChannel(editor, "internal", settings.internalAudio)
        editor.putFloat(LIVE_MASTER, settings.liveMasterGain)
        editor.putFloat(MONITOR_MASTER, settings.monitorMasterGain)
        editor.apply()
    }

    fun save(context: Context, settings: AudioMixerSettings) {
        persist(context, settings)
        AudioMixerControlBus.apply(settings)
    }

    private fun readChannel(
        prefs: android.content.SharedPreferences,
        prefix: String
    ): ChannelMixConfig = ChannelMixConfig(
        gain = prefs.getFloat("${prefix}_gain", 1f).coerceIn(0f, 2f),
        muted = prefs.getBoolean("${prefix}_muted", false),
        solo = prefs.getBoolean("${prefix}_solo", false),
        pan = prefs.getFloat("${prefix}_pan", 0f).coerceIn(-1f, 1f),
        routeToLive = prefs.getBoolean("${prefix}_live", true),
        routeToMonitor = prefs.getBoolean("${prefix}_monitor", true)
    )

    private fun writeChannel(
        editor: android.content.SharedPreferences.Editor,
        prefix: String,
        config: ChannelMixConfig
    ) {
        editor.putFloat("${prefix}_gain", config.gain)
        editor.putBoolean("${prefix}_muted", config.muted)
        editor.putBoolean("${prefix}_solo", config.solo)
        editor.putFloat("${prefix}_pan", config.pan)
        editor.putBoolean("${prefix}_live", config.routeToLive)
        editor.putBoolean("${prefix}_monitor", config.routeToMonitor)
    }
}

/** In-process bridge for applying UI changes to the currently active LIVE mixer. */
object AudioMixerControlBus {
    @Volatile private var activeSession: OssAudioMixerSession? = null

    fun attach(session: OssAudioMixerSession) {
        activeSession = session
    }

    fun detach(session: OssAudioMixerSession) {
        if (activeSession === session) activeSession = null
    }

    val state: OssAudioMixerSession.State?
        get() = activeSession?.state

    val captureSnapshot: RootEncoderAudioCaptureSession.Snapshot?
        get() = activeSession?.captureSnapshot

    fun channelPeak(index: Int): Float =
        activeSession?.let { runCatching { it.channelPeak(index) }.getOrDefault(0f) } ?: 0f

    fun apply(settings: AudioMixerSettings) {
        activeSession?.let { session ->
            session.updateChannelConfig(0, settings.microphone)
            session.updateChannelConfig(1, settings.internalAudio)
            session.setLiveMasterGain(settings.liveMasterGain)
            session.setMonitorMasterGain(settings.monitorMasterGain)
        }
    }
}
