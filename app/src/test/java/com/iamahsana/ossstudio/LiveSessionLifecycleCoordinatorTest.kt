package com.iamahsana.ossstudio

import com.iamahsana.ossstudio.stream.LiveSessionLifecycleCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionLifecycleCoordinatorTest {

    @Test
    fun shutdownRunsOwnersInRequiredOrderAndOnlyOnce() {
        val calls = mutableListOf<String>()
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = { calls += "detach" },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stop() { calls += "stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection"; true },
            diagnosticLog = { _, _, _, _ -> }
        )

        val first = coordinator.shutdown()
        val second = coordinator.shutdown()

        assertTrue(first.completed)
        assertEquals(emptyList<String>(), first.failedStages)
        assertEquals(first, second)
        assertEquals(listOf("detach", "stop", "projection", "release"), calls)
    }

    @Test
    fun shutdownContinuesAfterStageFailureAndReportsIt() {
        val calls = mutableListOf<String>()
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = { calls += "detach"; error("GL unavailable") },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stop() { calls += "stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection" },
            diagnosticLog = { _, _, _, _ -> }
        )

        val result = coordinator.shutdown()

        assertFalse(result.completed)
        assertEquals(listOf("screen_layer_detach"), result.failedStages)
        assertEquals(listOf("detach", "stop", "projection", "release"), calls)
    }

    @Test
    fun projectionReleaseFailureIsReportedAndRemainingCleanupContinues() {
        val calls = mutableListOf<String>()
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = { calls += "detach" },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stop() { calls += "stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection"; false },
            diagnosticLog = { _, _, _, _ -> }
        )

        val result = coordinator.shutdown()

        assertFalse(result.completed)
        assertEquals(listOf("projection_stop"), result.failedStages)
        assertEquals(listOf("detach", "stop", "projection", "release"), calls)
    }

    @Test
    fun diagnosticSinkFailureNeverInterruptsCleanup() {
        val calls = mutableListOf<String>()
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = { calls += "detach" },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stop() { calls += "stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection" },
            diagnosticLog = { _, _, _, _ -> error("diagnostics unavailable") }
        )

        val result = coordinator.shutdown()

        assertTrue(result.completed)
        assertEquals(emptyList<String>(), result.failedStages)
        assertEquals(listOf("detach", "stop", "projection", "release"), calls)
    }
}
