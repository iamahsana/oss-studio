package com.iamahsana.ossstudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTimestampAlignerTest {
    private val sampleRate = 48_000
    private val blockFrames = 480
    private val blockBytes = blockFrames * 4

    @Test fun alignsIndependentSourcesOnSameTimeline() {
        val mic = SpscPcmFrameQueue(2, blockBytes)
        val game = SpscPcmFrameQueue(2, blockBytes)
        assertTrue(mic.offer(pcm(blockFrames, 1000), 0, blockBytes, 0L))
        assertTrue(game.offer(pcm(blockFrames, 2000), 0, blockBytes, 0L))
        val aligner = PcmTimestampAligner(arrayOf(mic, game), sampleRate, blockBytes)
        val outputs = arrayOf(ByteArray(blockBytes), ByteArray(blockBytes))

        aligner.fillBlock(0L, blockFrames, outputs)

        assertEquals(1000, sample(outputs[0], 0))
        assertEquals(2000, sample(outputs[1], 0))
        assertEquals(0L, aligner.missingBlocks(0))
        assertEquals(0L, aligner.missingBlocks(1))
    }

    @Test fun futureFramesBecomeAvailableAtTheirTimestamp() {
        val queue = SpscPcmFrameQueue(2, blockBytes)
        assertTrue(queue.offer(pcm(blockFrames, 3000), 0, blockBytes, 10_000L))
        val aligner = PcmTimestampAligner(arrayOf(queue), sampleRate, blockBytes)
        val output = arrayOf(ByteArray(blockBytes))

        aligner.fillBlock(0L, blockFrames, output)
        assertEquals(0, sample(output[0], 0))
        assertEquals(1L, aligner.missingBlocks(0))

        aligner.fillBlock(10_000L, blockFrames, output)
        assertEquals(3000, sample(output[0], 0))
    }

    @Test fun discardsFramesThatAreEntirelyLate() {
        val queue = SpscPcmFrameQueue(2, blockBytes)
        assertTrue(queue.offer(pcm(blockFrames, 4000), 0, blockBytes, 0L))
        val aligner = PcmTimestampAligner(arrayOf(queue), sampleRate, blockBytes)
        val output = arrayOf(ByteArray(blockBytes))

        aligner.fillBlock(10_000L, blockFrames, output)

        assertEquals(1L, aligner.lateFrames(0))
        assertEquals(0, sample(output[0], 0))
    }

    @Test fun retainsFrameThatSpansMultipleBlocks() {
        val queue = SpscPcmFrameQueue(2, blockBytes * 2)
        val twentyMs = pcm(blockFrames * 2, 5000)
        assertTrue(queue.offer(twentyMs, 0, twentyMs.size, 0L))
        val aligner = PcmTimestampAligner(arrayOf(queue), sampleRate, blockBytes * 2)
        val output = arrayOf(ByteArray(blockBytes))

        aligner.fillBlock(0L, blockFrames, output)
        assertEquals(5000, sample(output[0], 0))

        aligner.fillBlock(10_000L, blockFrames, output)
        assertEquals(5000, sample(output[0], 0))
        assertEquals(0L, aligner.lateFrames(0))
    }

    @Test fun combinesAdjacentFramesWithinOneBlock() {
        val queue = SpscPcmFrameQueue(2, blockBytes)
        val halfFrames = blockFrames / 2
        val halfBytes = halfFrames * 4
        assertTrue(queue.offer(pcm(halfFrames, 6000), 0, halfBytes, 0L))
        assertTrue(queue.offer(pcm(halfFrames, 7000), 0, halfBytes, 5_000L))
        val aligner = PcmTimestampAligner(arrayOf(queue), sampleRate, blockBytes)
        val output = arrayOf(ByteArray(blockBytes))

        aligner.fillBlock(0L, blockFrames, output)

        assertEquals(6000, sample(output[0], 0))
        assertEquals(7000, sample(output[0], halfFrames))
    }

    @Test fun countsMissingBlockWhenSourceHasNoFrames() {
        val queue = SpscPcmFrameQueue(2, blockBytes)
        val aligner = PcmTimestampAligner(arrayOf(queue), sampleRate, blockBytes)
        aligner.fillBlock(0L, blockFrames, arrayOf(ByteArray(blockBytes)))
        assertEquals(1L, aligner.missingBlocks(0))
    }

    private fun pcm(frames: Int, value: Int): ByteArray {
        val bytes = ByteArray(frames * 4)
        var offset = 0
        while (offset < bytes.size) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = (value shr 8).toByte()
            bytes[offset + 2] = (value and 0xFF).toByte()
            bytes[offset + 3] = (value shr 8).toByte()
            offset += 4
        }
        return bytes
    }

    private fun sample(data: ByteArray, frame: Int): Int {
        val offset = frame * 4
        return ((data[offset].toInt() and 0xFF) or (data[offset + 1].toInt() shl 8)).toShort().toInt()
    }
}
