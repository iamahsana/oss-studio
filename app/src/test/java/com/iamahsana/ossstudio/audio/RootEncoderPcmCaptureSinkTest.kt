package com.iamahsana.ossstudio.audio

import com.pedro.encoder.Frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootEncoderPcmCaptureSinkTest {

    @Test
    fun ignoresFramesUntilCaptureBegins() {
        val queue = SpscPcmFrameQueue(capacity = 2, maxFrameBytes = 32)
        val sink = RootEncoderPcmCaptureSink(queue)

        sink.inputPCMData(Frame(byteArrayOf(1, 2, 3, 4), 0, 4, 123L))

        assertEquals(1L, sink.ignoredFrames)
        assertEquals(0, queue.queuedFrames)
    }

    @Test
    fun copiesOnlyFrameRangeAndPreservesTimestamp() {
        val queue = SpscPcmFrameQueue(capacity = 2, maxFrameBytes = 32)
        val sink = RootEncoderPcmCaptureSink(queue)
        sink.beginCapture()

        sink.inputPCMData(Frame(byteArrayOf(9, 8, 1, 2, 3, 4, 7), 2, 4, 987654L))

        val target = ByteArray(8)
        val info = SpscPcmFrameQueue.FrameInfo()
        assertTrue(queue.poll(target, info))
        assertEquals(4, info.size)
        assertEquals(987654L, info.timestampMicros)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.copyOf(info.size))
    }

    @Test
    fun stopsAcceptingFramesImmediately() {
        val queue = SpscPcmFrameQueue(capacity = 2, maxFrameBytes = 32)
        val sink = RootEncoderPcmCaptureSink(queue)
        sink.beginCapture()
        sink.endCapture()

        sink.inputPCMData(Frame(byteArrayOf(1, 2, 3, 4), 0, 4, 123L))

        assertFalse(queue.queuedFrames > 0)
        assertEquals(1L, sink.ignoredFrames)
    }
}
