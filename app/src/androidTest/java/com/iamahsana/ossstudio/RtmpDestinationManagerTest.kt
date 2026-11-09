package com.iamahsana.ossstudio

import android.media.MediaCodec
import com.iamahsana.ossstudio.stream.rtmp.RtmpDestinationManager
import com.iamahsana.ossstudio.stream.rtmp.RtmpDestinationPort
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpDestinationManagerTest {

    private class FakeDestination(
        override val destinationId: String,
        override var isStreaming: Boolean = true,
        private val failVideo: Boolean = false
    ) : RtmpDestinationPort {
        var videoFrames = 0
        var audioFrames = 0
        var disconnectCalls = 0

        override fun setVideoCodec(codec: VideoCodec) = Unit
        override fun setAudioCodec(codec: AudioCodec) = Unit
        override fun setVideoFormat(width: Int, height: Int, fps: Int) = Unit
        override fun setAudioFormat(sampleRate: Int, stereo: Boolean) = Unit
        override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) = Unit
        override fun connect(endpoint: String) = Unit
        override fun disconnect() {
            disconnectCalls++
            isStreaming = false
        }

        override fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (failVideo) error("simulated destination failure")
            videoFrames++
        }

        override fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            audioFrames++
        }
    }

    @Test
    fun videoDispatchFailureDoesNotInterruptOtherDestinations() {
        val manager = RtmpDestinationManager()
        val failing = FakeDestination("destination-a", failVideo = true)
        val healthy = FakeDestination("destination-b")
        manager.add(failing)
        manager.add(healthy)

        manager.dispatchVideo(ByteBuffer.allocate(16), MediaCodec.BufferInfo())

        assertEquals(1, healthy.videoFrames)
        assertEquals(1L, manager.getDispatchFailureCounts().first)
    }

    @Test
    fun onlyConnectedDestinationsReceiveMedia() {
        val manager = RtmpDestinationManager()
        val connected = FakeDestination("connected")
        val disconnected = FakeDestination("disconnected", isStreaming = false)
        manager.add(connected)
        manager.add(disconnected)

        manager.dispatchAudio(ByteBuffer.allocate(16), MediaCodec.BufferInfo())

        assertEquals(1, connected.audioFrames)
        assertEquals(0, disconnected.audioFrames)
    }

    @Test
    fun duplicateIdsAreRejectedAndRemovalIsIsolated() {
        val manager = RtmpDestinationManager()
        val first = FakeDestination("same-id")
        val duplicate = FakeDestination("same-id")
        assertTrue(manager.add(first))
        assertFalse(manager.add(duplicate))
        assertTrue(manager.remove("same-id"))
        assertEquals(1, first.disconnectCalls)
        assertEquals(0, manager.destinationCount)
    }
}
