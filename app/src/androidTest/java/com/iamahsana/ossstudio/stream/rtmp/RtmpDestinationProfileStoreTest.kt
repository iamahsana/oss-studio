package com.iamahsana.ossstudio.stream.rtmp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RtmpDestinationProfileStoreTest {
    @Test fun roundTripsCredentialsWithoutWritingPlaintextToPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = RtmpDestinationProfile(
            id = "test-destination",
            name = "Test stream",
            serverUrl = "rtmps://live.example.com/app",
            streamKey = "secret-key-123"
        )
        try {
            RtmpDestinationProfileStore.save(context, listOf(profile))
            val blob = context.getSharedPreferences("oss_rtmp_destination_vault", Context.MODE_PRIVATE)
                .getString("encrypted_profiles", "") ?: ""
            assertFalse(blob.contains(profile.serverUrl))
            assertFalse(blob.contains(profile.streamKey))
            assertEquals(listOf(profile), RtmpDestinationProfileStore.load(context))
        } finally {
            RtmpDestinationProfileStore.clear(context)
        }
    }

    @Test fun rejectsCredentialsEmbeddedInServerUrlAndUnsupportedSchemes() {
        assertThrows(IllegalArgumentException::class.java) {
            RtmpDestinationProfile(name = "Bad", serverUrl = "rtmp://user:pass@example.com/live", streamKey = "key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RtmpDestinationProfile(name = "Bad", serverUrl = "https://example.com/live", streamKey = "key")
        }
    }

    @Test fun authenticatedEncryptionRejectsTamperedCiphertext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            RtmpDestinationProfileStore.save(
                context,
                listOf(RtmpDestinationProfile(name = "Tamper", serverUrl = "rtmp://live.example.com/app", streamKey = "key"))
            )
            val prefs = context.getSharedPreferences("oss_rtmp_destination_vault", Context.MODE_PRIVATE)
            val blob = prefs.getString("encrypted_profiles", null)!!
            val changed = blob.dropLast(2) + if (blob.takeLast(2) == "AA") "BB" else "AA"
            prefs.edit().putString("encrypted_profiles", changed).commit()
            assertThrows(Exception::class.java) { RtmpDestinationProfileStore.load(context) }
        } finally {
            RtmpDestinationProfileStore.clear(context)
        }
    }
    @Test fun endpointCombinesServerAndKeyWithoutDuplicateSeparators() {
        val profile = RtmpDestinationProfile(
            name = "Endpoint",
            serverUrl = "rtmp://live.example.com/app/",
            streamKey = "/stream-key"
        )
        assertEquals("rtmp://live.example.com/app/stream-key", profile.endpoint())
        assertThrows(IllegalArgumentException::class.java) {
            RtmpDestinationProfile(name = "Whitespace", serverUrl = " rtmp://live.example.com/app", streamKey = "key")
        }
    }

}
