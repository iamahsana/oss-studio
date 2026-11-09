package com.iamahsana.ossstudio

import com.iamahsana.ossstudio.stream.LiveSessionController
import com.iamahsana.ossstudio.stream.LiveSessionLifecycleCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionControllerTest {

    private class FakeRuntime : LiveSessionController.Runtime {
        val calls = mutableListOf<String>()
        var rtmpListener: LiveSessionController.RtmpListener? = null
        var screenListener: LiveSessionController.ScreenListener? = null
        var prepareResult = true
        var projectionStopResult = true
        var failFirstAudioStop = false
        var audioStopAttempts = 0
        var deferAudioStop = false
        var pendingAudioCompletion: ((com.iamahsana.ossstudio.stream.AudioStopStatus) -> Unit)? = null

        override fun prepare(): Boolean {
            calls += "prepare"
            return prepareResult
        }

        override fun startRtmp(endpoint: String, listener: LiveSessionController.RtmpListener) {
            calls += "start_rtmp"
            rtmpListener = listener
        }

        override fun attachScreen(listener: LiveSessionController.ScreenListener) {
            calls += "attach_screen"
            screenListener = listener
        }

        override fun detachScreen() { calls += "detach_screen" }
        override fun stopRtmp() { calls += "stop_rtmp" }
        override fun stopAudio(completion: (com.iamahsana.ossstudio.stream.AudioStopStatus) -> Unit) {
            audioStopAttempts++
            calls += "stop_audio_$audioStopAttempts"
            val failed = failFirstAudioStop && audioStopAttempts == 1
            val status = com.iamahsana.ossstudio.stream.AudioStopStatus(
                mixerStopped = !failed,
                captureStopped = !failed,
                monitorStopped = !failed
            )
            if (deferAudioStop) pendingAudioCompletion = completion
            else completion(status)
        }

        fun completePendingAudioStop() {
            pendingAudioCompletion?.invoke(
                com.iamahsana.ossstudio.stream.AudioStopStatus(true, true, true)
            )
            pendingAudioCompletion = null
        }
        override fun stopProjection(): Boolean {
            calls += "stop_projection"
            return projectionStopResult
        }
        override fun releaseRtmp() { calls += "release_rtmp" }
    }

    @Test
    fun becomesLiveOnlyAfterRtmpAndScreenAreReady() {
        val fake = FakeRuntime()
        val controller = LiveSessionController(object : LiveSessionController.RuntimeFactory {
            override fun create() = fake
        })

        assertTrue(controller.start("rtmps://stream.example/live/key"))
        assertEquals(LiveSessionController.State.STARTING, controller.state)
        fake.rtmpListener?.onConnected()
        assertEquals(LiveSessionController.State.STARTING, controller.state)
        fake.screenListener?.onReady()
        assertEquals(LiveSessionController.State.LIVE, controller.state)
        assertEquals(listOf("prepare", "start_rtmp", "attach_screen"), fake.calls)
    }

    @Test
    fun stopCleansResourcesInOwnerOrder() {
        val fake = FakeRuntime()
        val controller = LiveSessionController(object : LiveSessionController.RuntimeFactory {
            override fun create() = fake
        })

        controller.start("rtmp://stream.example/live/key")
        fake.rtmpListener?.onConnected()
        fake.screenListener?.onReady()
        val result = controller.stop()

        assertNotNull(result)
        assertTrue(result!!.completed)
        assertEquals(LiveSessionController.State.STOPPED, controller.state)
        assertEquals(
            listOf("prepare", "start_rtmp", "attach_screen", "detach_screen",
                "stop_audio_1", "stop_rtmp", "stop_projection", "release_rtmp"),
            fake.calls
        )
    }

    @Test
    fun failedStartupCleansUpAndAllowsFreshRestart() {
        val first = FakeRuntime().apply { prepareResult = false }
        val second = FakeRuntime()
        val created = ArrayDeque(listOf(first, second))
        val controller = LiveSessionController(object : LiveSessionController.RuntimeFactory {
            override fun create() = created.removeFirst()
        })

        assertFalse(controller.start("rtmp://stream.example/live/key"))
        assertEquals(LiveSessionController.State.FAILED, controller.state)
        assertEquals(
            listOf("prepare", "detach_screen", "stop_audio_1", "stop_rtmp", "stop_projection", "release_rtmp"),
            first.calls
        )

        assertTrue(controller.start("rtmp://stream.example/live/key"))
        second.rtmpListener?.onConnected()
        second.screenListener?.onReady()
        assertEquals(LiveSessionController.State.LIVE, controller.state)
    }

    @Test
    fun screenFailureStopsRtmpAndMarksSessionFailed() {
        val fake = FakeRuntime()
        val controller = LiveSessionController(object : LiveSessionController.RuntimeFactory {
            override fun create() = fake
        })

        controller.start("rtmp://stream.example/live/key")
        fake.screenListener?.onFailure()

        assertEquals(LiveSessionController.State.FAILED, controller.state)
        assertTrue(fake.calls.containsAll(listOf(
            "detach_screen", "stop_rtmp", "stop_projection", "release_rtmp"
        )))
    }

    @Test
    fun runtimeFactoryFailureMarksSessionFailedAndAllowsRetry() {
        val fake = FakeRuntime()
        var attempts = 0
        val controller = LiveSessionController(object : LiveSessionController.RuntimeFactory {
            override fun create(): LiveSessionController.Runtime {
                attempts += 1
                if (attempts == 1) error("factory failure")
                return fake
            }
        })

        assertFalse(controller.start("rtmp://stream.example/live/key"))
        assertEquals(LiveSessionController.State.FAILED, controller.state)
        assertTrue(controller.start("rtmp://stream.example/live/key"))
        assertEquals(listOf("prepare", "start_rtmp", "attach_screen"), fake.calls)
    }

    @Test
    fun incompleteAudioCleanupCanBeRetriedWithoutStartingNewSession() {
        val fake = FakeRuntime().apply { failFirstAudioStop = true }
        val controller = LiveSessionController(object : LiveSessionController.RuntimeFactory {
            override fun create() = fake
        })
        assertTrue(controller.start("rtmp://stream.example/live/key"))

        var first: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        controller.stopAsync { first = it }
        assertFalse(first?.completed == true)
        assertEquals(LiveSessionController.State.FAILED, controller.state)
        assertFalse(runCatching { controller.start("rtmp://stream.example/live/other") }.isSuccess)

        var second: LiveSessionLifecycleCoordinator.ShutdownResult? = null
        controller.stopAsync { second = it }
        assertTrue(second?.completed == true)
        assertEquals(LiveSessionController.State.STOPPED, controller.state)
        assertEquals(2, fake.audioStopAttempts)
    }


    @Test
    fun concurrentStopRequestsAllReceiveTheSameCompletion() {
        val fake = FakeRuntime().apply { deferAudioStop = true }
        val controller = LiveSessionController(object : LiveSessionController.RuntimeFactory {
            override fun create() = fake
        })
        assertTrue(controller.start("rtmp://stream.example/live/key"))

        val results = mutableListOf<LiveSessionLifecycleCoordinator.ShutdownResult>()
        controller.stopAsync { results += it }
        controller.stopAsync { results += it }

        assertTrue(results.isEmpty())
        assertEquals(1, fake.audioStopAttempts)

        fake.completePendingAudioStop()

        assertEquals(2, results.size)
        assertTrue(results.all { it.completed })
        assertEquals(LiveSessionController.State.STOPPED, controller.state)
    }

    @Test
    fun projectionStopFailureIsReportedByTeardown() {
        val fake = FakeRuntime().apply { projectionStopResult = false }
        val controller = LiveSessionController(object : LiveSessionController.RuntimeFactory {
            override fun create() = fake
        })

        controller.start("rtmp://stream.example/live/key")
        val result = controller.stop()

        assertNotNull(result)
        assertFalse(result!!.completed)
        assertEquals(listOf("projection_stop"), result.failedStages)
        assertEquals(LiveSessionController.State.FAILED, controller.state)
    }
}
