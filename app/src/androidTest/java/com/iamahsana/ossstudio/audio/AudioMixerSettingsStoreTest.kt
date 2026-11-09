package com.iamahsana.ossstudio.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioMixerSettingsStoreTest {
    @Test fun savesAndRestoresIndependentChannelAndMasterSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = AudioMixerSettings(
            microphone = ChannelMixConfig(
                gain = 1.35f,
                muted = true,
                solo = false,
                pan = -0.25f,
                routeToLive = true,
                routeToMonitor = false
            ),
            internalAudio = ChannelMixConfig(
                gain = 0.8f,
                muted = false,
                solo = true,
                pan = 0.4f,
                routeToLive = false,
                routeToMonitor = true
            ),
            liveMasterGain = 1.5f,
            monitorMasterGain = 0.65f
        )
        try {
            AudioMixerSettingsStore.persist(context, expected)
            assertEquals(expected, AudioMixerSettingsStore.load(context))
        } finally {
            context.getSharedPreferences("oss_audio_mixer", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test fun clampsCorruptedPreferenceValuesToSupportedRanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            context.getSharedPreferences("oss_audio_mixer", Context.MODE_PRIVATE).edit()
                .putFloat("mic_gain", 5f)
                .putFloat("mic_pan", -5f)
                .putFloat("live_master", 9f)
                .commit()
            val loaded = AudioMixerSettingsStore.load(context)
            assertEquals(2f, loaded.microphone.gain)
            assertEquals(-1f, loaded.microphone.pan)
            assertEquals(2f, loaded.liveMasterGain)
        } finally {
            context.getSharedPreferences("oss_audio_mixer", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }
}
