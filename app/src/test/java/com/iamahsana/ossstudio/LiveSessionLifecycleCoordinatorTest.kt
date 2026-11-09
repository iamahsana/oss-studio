package com.iamahsana.ossstudio

import com.iamahsana.ossstudio.stream.AudioStopStatus
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
    fun screenLayerFailureRetainsGlOwnersForRetry() {
        val calls = mutableListOf<String>()
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = { calls += "detach"; error("GL unavailable") },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stop() { calls += "stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection"; true },
            diagnosticLog = { _, _, _, _ -> }
        )

        val result = coordinator.shutdown()

        assertFalse(result.completed)
        assertEquals(
            listOf("screen_layer_detach", "rtmp_stop_skipped",
                "projection_stop_skipped", "rtmp_release_skipped"),
            result.failedStages
        )
        assertEquals(listOf("detach"), calls)
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
    fun asyncShutdownStopsAudioBeforeRtmpAndProjection() {
        val calls = mutableListOf<String>()
        var audioCompletion: ((AudioStopStatus) -> Unit)? = null
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = { calls += "detach" },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stopAudio(completion: (AudioStopStatus) -> Unit) {
                    calls += "audio"
                    audioCompletion = completion
                }
                override fun stop() { calls += "rtmp_stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection"; true },
            diagnosticLog = { _, _, _, _ -> }
        )

        var result: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        coordinator.shutdownAsync { result = it }
        assertEquals(listOf("detach", "audio"), calls)
        assertEquals(null, result)

        audioCompletion?.invoke(AudioStopStatus(true, true, true))
        assertEquals(listOf("detach", "audio", "rtmp_stop", "projection", "release"), calls)
        assertTrue(result?.completed == true)
    }


    @Test
    fun asyncScreenLayerFailureRetainsRtmpAndProjectionForRetry() {
        val calls = mutableListOf<String>()
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = { calls += "detach"; error("GL filter still attached") },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stopAudio(completion: (AudioStopStatus) -> Unit) {
                    calls += "audio"
                    completion(AudioStopStatus(true, true, true))
                }
                override fun stop() { calls += "rtmp_stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection"; true },
            diagnosticLog = { _, _, _, _ -> }
        )

        var result: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        coordinator.shutdownAsync { result = it }

        assertEquals(listOf("detach", "audio"), calls)
        assertFalse(result?.completed == true)
        assertEquals(
            listOf(
                "screen_layer_detach", "rtmp_stop_skipped",
                "projection_stop_skipped", "rtmp_release_skipped"
            ),
            result?.failedStages
        )
    }


    @Test
    fun asyncScreenLayerCleanupCanRetryBeforeReleasingGlOwners() {
        val calls = mutableListOf<String>()
        var detachAttempts = 0
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = {
                detachAttempts++
                calls += "detach_$detachAttempts"
                if (detachAttempts == 1) error("temporary GL detach failure")
            },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stopAudio(completion: (AudioStopStatus) -> Unit) {
                    calls += "audio"
                    completion(AudioStopStatus(true, true, true))
                }
                override fun stop() { calls += "rtmp_stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection"; true },
            diagnosticLog = { _, _, _, _ -> }
        )

        var first: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        var second: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        coordinator.shutdownAsync { first = it }
        assertFalse(first?.completed == true)
        assertFalse(calls.contains("rtmp_stop"))

        coordinator.shutdownAsync { second = it }
        assertTrue(second?.completed == true)
        assertEquals(
            listOf(
                "detach_1", "audio",
                "detach_2", "audio", "rtmp_stop", "projection", "release"
            ),
            calls
        )
    }

    @Test
    fun asyncShutdownContinuesAfterAudioTeardownFailure() {
        val calls = mutableListOf<String>()
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = { calls += "detach" },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stopAudio(completion: (AudioStopStatus) -> Unit) {
                    calls += "audio"
                    completion(AudioStopStatus(false, false, false))
                }
                override fun stop() { calls += "rtmp_stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection"; true },
            diagnosticLog = { _, _, _, _ -> }
        )

        var result: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        coordinator.shutdownAsync { result = it }

        assertEquals(listOf("detach", "audio"), calls)
        assertFalse(result?.completed == true)
        assertEquals(
            listOf("audio_stop", "rtmp_stop_skipped", "projection_stop_skipped", "rtmp_release_skipped"),
            result?.failedStages
        )
    }

    @Test
    fun asyncShutdownCanRetryAfterAudioWorkerCleanupFailure() {
        val calls = mutableListOf<String>()
        var attempt = 0
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = { calls += "detach" },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stopAudio(completion: (AudioStopStatus) -> Unit) {
                    attempt++
                    calls += "audio_$attempt"
                    completion(
                        if (attempt == 1) AudioStopStatus(false, false, false)
                        else AudioStopStatus(true, true, true)
                    )
                }
                override fun stop() { calls += "rtmp_stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection"; true },
            diagnosticLog = { _, _, _, _ -> }
        )

        var first: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        var second: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        coordinator.shutdownAsync { first = it }
        assertFalse(first?.completed == true)
        assertFalse(calls.contains("release"))

        coordinator.shutdownAsync { second = it }
        assertTrue(second?.completed == true)
        assertEquals(
            listOf(
                "detach", "audio_1",
                "detach", "audio_2", "rtmp_stop", "projection", "release"
            ),
            calls
        )
    }


    @Test
    fun asyncShutdownKeepsProjectionWhenCaptureCleanupIsIncomplete() {
        val calls = mutableListOf<String>()
        val coordinator = LiveSessionLifecycleCoordinator(
            screenLayer = { calls += "detach" },
            streamPipeline = object : LiveSessionLifecycleCoordinator.StreamPipeline {
                override fun stopAudio(completion: (AudioStopStatus) -> Unit) {
                    calls += "audio"
                    completion(AudioStopStatus(true, false, true))
                }
                override fun stop() { calls += "rtmp_stop" }
                override fun release() { calls += "release" }
            },
            projectionSession = { calls += "projection"; true },
            diagnosticLog = { _, _, _, _ -> }
        )

        var result: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        coordinator.shutdownAsync { result = it }

        assertEquals(listOf("detach", "audio", "rtmp_stop", "release"), calls)
        assertFalse(result?.completed == true)
        assertEquals(
            listOf("audio_stop", "projection_stop_skipped"),
            result?.failedStages
        )
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
            projectionSession = { calls += "projection"; true },
            diagnosticLog = { _, _, _, _ -> error("diagnostics unavailable") }
        )

        val result = coordinator.shutdown()

        assertTrue(result.completed)
        assertEquals(emptyList<String>(), result.failedStages)
        assertEquals(listOf("detach", "stop", "projection", "release"), calls)
    }
}
