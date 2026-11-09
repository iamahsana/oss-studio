package com.iamahsana.ossstudio.stream.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiRtmpStateReducerTest {
    @Test fun oneConnectedDestinationKeepsSessionLiveWhenAnotherFails() {
        assertEquals(
            MultiRtmpStreamSession.State.LIVE,
            reduceMultiRtmpState(
                listOf(
                    MultiRtmpStreamSession.DestinationState.CONNECTED,
                    MultiRtmpStreamSession.DestinationState.FAILED
                )
            )
        )
    }

    @Test fun sessionFailsOnlyWhenEveryDestinationIsTerminalAndNoneConnected() {
        assertEquals(
            MultiRtmpStreamSession.State.FAILED,
            reduceMultiRtmpState(
                listOf(
                    MultiRtmpStreamSession.DestinationState.AUTH_ERROR,
                    MultiRtmpStreamSession.DestinationState.FAILED
                )
            )
        )
    }

    @Test fun pendingDestinationPreventsPrematureFailure() {
        assertEquals(
            MultiRtmpStreamSession.State.CONNECTING,
            reduceMultiRtmpState(
                listOf(
                    MultiRtmpStreamSession.DestinationState.FAILED,
                    MultiRtmpStreamSession.DestinationState.CONNECTING
                )
            )
        )
        assertNull(reduceMultiRtmpState(listOf(MultiRtmpStreamSession.DestinationState.IDLE)))
    }

    @Test fun allDisconnectedDestinationsProduceStoppedState() {
        assertEquals(
            MultiRtmpStreamSession.State.STOPPED,
            reduceMultiRtmpState(
                listOf(
                    MultiRtmpStreamSession.DestinationState.DISCONNECTED,
                    MultiRtmpStreamSession.DestinationState.DISCONNECTED
                )
            )
        )
    }
}
