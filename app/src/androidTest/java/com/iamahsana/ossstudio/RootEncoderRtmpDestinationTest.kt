package com.iamahsana.ossstudio

import com.iamahsana.ossstudio.stream.rtmp.RootEncoderRtmpDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class RootEncoderRtmpDestinationTest {

    @Test
    fun upstreamConnectionCallbacksUpdateDestinationState() {
        val states = mutableListOf<RootEncoderRtmpDestination.State>()
        val destination = RootEncoderRtmpDestination("test-destination") { _, state ->
            states += state
        }

        assertEquals(RootEncoderRtmpDestination.State.DISCONNECTED, destination.state)

        destination.onConnectionStarted("rtmp://example.invalid/live/secret-key")
        assertEquals(RootEncoderRtmpDestination.State.CONNECTING, destination.state)

        destination.onConnectionSuccess()
        assertEquals(RootEncoderRtmpDestination.State.CONNECTED, destination.state)

        destination.onAuthError()
        assertEquals(RootEncoderRtmpDestination.State.AUTH_ERROR, destination.state)

        destination.onDisconnect()
        assertEquals(RootEncoderRtmpDestination.State.DISCONNECTED, destination.state)

        assertEquals(
            listOf(
                RootEncoderRtmpDestination.State.CONNECTING,
                RootEncoderRtmpDestination.State.CONNECTED,
                RootEncoderRtmpDestination.State.AUTH_ERROR,
                RootEncoderRtmpDestination.State.DISCONNECTED
            ),
            states
        )
    }
}
