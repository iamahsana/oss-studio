package com.iamahsana.ossstudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSourceCleanupTest {

    @Test
    fun failedStopRetainsSourceAndRetriesBeforeRelease() {
        var stopCalls = 0
        var releaseCalls = 0
        val cleanup = CaptureSourceCleanup(
            stopSource = {
                stopCalls++
                if (stopCalls == 1) error("transient stop failure")
            },
            releaseSource = { releaseCalls++ }
        )
        cleanup.markStartAttempted()

        assertFalse(cleanup.cleanup())
        assertEquals(1, stopCalls)
        assertEquals(0, releaseCalls)

        assertTrue(cleanup.cleanup())
        assertEquals(2, stopCalls)
        assertEquals(1, releaseCalls)
        assertTrue(cleanup.cleanup())
        assertEquals(2, stopCalls)
        assertEquals(1, releaseCalls)
    }

    @Test
    fun failedReleaseRetriesWithoutRepeatingSuccessfulStop() {
        var stopCalls = 0
        var releaseCalls = 0
        val cleanup = CaptureSourceCleanup(
            stopSource = { stopCalls++ },
            releaseSource = {
                releaseCalls++
                if (releaseCalls == 1) error("transient release failure")
            }
        )
        cleanup.markStartAttempted()

        assertFalse(cleanup.cleanup())
        assertTrue(cleanup.cleanup())
        assertEquals(1, stopCalls)
        assertEquals(2, releaseCalls)
    }

    @Test
    fun sourceThatNeverStartedSkipsStopButStillReleases() {
        var stopCalls = 0
        var releaseCalls = 0
        val cleanup = CaptureSourceCleanup(
            stopSource = { stopCalls++ },
            releaseSource = { releaseCalls++ }
        )

        assertTrue(cleanup.cleanup())
        assertEquals(0, stopCalls)
        assertEquals(1, releaseCalls)
    }
}
