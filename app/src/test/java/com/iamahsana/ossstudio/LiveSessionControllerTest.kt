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
                "stop_rtmp", "stop_projection", "release_rtmp"),
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
            listOf("prepare", "detach_screen", "stop_rtmp", "stop_projection", "release_rtmp"),
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
