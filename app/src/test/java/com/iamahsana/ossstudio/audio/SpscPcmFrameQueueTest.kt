package com.iamahsana.ossstudio.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpscPcmFrameQueueTest {
    @Test fun preservesFifoPayloadAndTimestamps() {
        val queue = SpscPcmFrameQueue(capacity = 2, maxFrameBytes = 8)
        val info = SpscPcmFrameQueue.FrameInfo()
        val output = ByteArray(8)

        assertTrue(queue.offer(byteArrayOf(1, 2, 3), 0, 3, 100L))
        assertTrue(queue.offer(byteArrayOf(4, 5), 0, 2, 200L))
        assertTrue(queue.poll(output, info))
        assertArrayEquals(byteArrayOf(1, 2, 3), output.copyOf(info.size))
        assertEquals(100L, info.timestampMicros)
        assertTrue(queue.poll(output, info))
        assertArrayEquals(byteArrayOf(4, 5), output.copyOf(info.size))
        assertEquals(200L, info.timestampMicros)
        assertFalse(queue.poll(output, info))
    }

    @Test fun dropsNewestFrameWhenFullWithoutBlocking() {
        val queue = SpscPcmFrameQueue(capacity = 1, maxFrameBytes = 4)
        val info = SpscPcmFrameQueue.FrameInfo()
        val output = ByteArray(4)

        assertTrue(queue.offer(byteArrayOf(1), 0, 1, 1L))
        assertFalse(queue.offer(byteArrayOf(2), 0, 1, 2L))
        assertEquals(1L, queue.droppedFrames)
        assertTrue(queue.poll(output, info))
        assertEquals(1, output[0].toInt())
    }

    @Test fun rejectsOversizedFrameAndCountsIt() {
        val queue = SpscPcmFrameQueue(capacity = 2, maxFrameBytes = 2)
        assertFalse(queue.offer(byteArrayOf(1, 2, 3), 0, 3, 10L))
        assertEquals(1L, queue.oversizedFrames)
        assertEquals(1L, queue.droppedFrames)
        assertEquals(0, queue.queuedFrames)
    }

    @Test fun tooSmallConsumerBufferDoesNotConsumeFrame() {
        val queue = SpscPcmFrameQueue(capacity = 1, maxFrameBytes = 8)
        val info = SpscPcmFrameQueue.FrameInfo()
        assertTrue(queue.offer(byteArrayOf(1, 2, 3, 4), 0, 4, 99L))
        assertFalse(queue.poll(ByteArray(2), info))
        assertEquals(1, queue.queuedFrames)
        val output = ByteArray(4)
        assertTrue(queue.poll(output, info))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output)
        assertEquals(99L, info.timestampMicros)
    }

    @Test fun supportsNonZeroSourceOffset() {
        val queue = SpscPcmFrameQueue(capacity = 1, maxFrameBytes = 4)
        val source = byteArrayOf(9, 8, 7, 6, 5)
        assertTrue(queue.offer(source, 1, 3, 5L))
        val output = ByteArray(4)
        val info = SpscPcmFrameQueue.FrameInfo()
        assertTrue(queue.poll(output, info))
        assertArrayEquals(byteArrayOf(8, 7, 6), output.copyOf(info.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSourceRangeIsRejected() {
        SpscPcmFrameQueue().offer(byteArrayOf(1), 1, 2, 0L)
    }
}
