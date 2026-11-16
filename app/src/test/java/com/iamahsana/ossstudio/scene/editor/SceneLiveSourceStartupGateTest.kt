package com.iamahsana.ossstudio.scene.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLiveSourceStartupGateTest {
    @Test
    fun waitsForEverySurfaceAndAsynchronousSource() {
        val gate = SceneLiveSourceStartupGate(expectedSurfaceCount = 2)
        gate.awaitSource("video-1")
        gate.awaitSource("camera-1")

        gate.surfaceReady()
        assertFalse(gate.isReady)
        assertTrue(gate.sourceReady("video-1"))
        assertFalse(gate.isReady)

        gate.surfaceReady()
        assertFalse(gate.isReady)
        assertTrue(gate.sourceReady("camera-1"))
        assertTrue(gate.isReady)
    }

    @Test
    fun unknownOrDuplicateReadySignalsCannotReleaseAnotherSource() {
        val gate = SceneLiveSourceStartupGate(expectedSurfaceCount = 1)
        gate.awaitSource("video-1")

        assertFalse(gate.sourceReady("unknown"))
        assertTrue(gate.isPending("video-1"))
        assertTrue(gate.sourceReady("video-1"))
        assertFalse(gate.sourceReady("video-1"))
        gate.surfaceReady()
        assertTrue(gate.isReady)
    }

    @Test
    fun rejectsDuplicatePendingSourcesAndExcessSurfaces() {
        val gate = SceneLiveSourceStartupGate(expectedSurfaceCount = 1)
        gate.awaitSource("camera-1")
        assertTrue(runCatching { gate.awaitSource("camera-1") }.isFailure)

        gate.surfaceReady()
        assertTrue(runCatching { gate.surfaceReady() }.isFailure)
    }
}
