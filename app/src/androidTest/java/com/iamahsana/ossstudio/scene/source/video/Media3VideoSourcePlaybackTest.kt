package com.iamahsana.ossstudio.scene.source.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pedro.extrasources.Media3VideoSource
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Media3VideoSourcePlaybackTest {
    @Test
    fun videoSourceRendersARealFrameAndReleasesItsPlayer() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val file = File(appContext.cacheDir, "oss-video-smoke-${UUID.randomUUID()}.mp4")
        testContext.assets.open("scene-video-smoke.mp4").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val surfaceTexture = SurfaceTexture(false)
        val source = Media3VideoSource(
            context = appContext,
            path = Uri.fromFile(file),
            loopMode = false
        )
        val firstFrame = CountDownLatch(1)
        val playbackError = AtomicReference<PlaybackException?>(null)

        try {
            assertTrue("The test clip must contain a decodable video track", source.init(64, 64, 10, 0))
            source.start(surfaceTexture)
            val player = requireNotNull(source.getPlayer())
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    firstFrame.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackError.set(error)
                    firstFrame.countDown()
                }
            })

            assertTrue(
                "Media3 did not render a video frame within 15 seconds",
                firstFrame.await(15, TimeUnit.SECONDS)
            )
            playbackError.get()?.let { throw AssertionError("Media3 failed to decode the test clip", it) }
            assertTrue("The video source should remain active after its first frame", source.isRunning)
        } finally {
            source.stop()
            surfaceTexture.release()
            file.delete()
        }

        assertFalse("Stopping the source must clear its running state", source.isRunning)
        assertNull("Stopping the source must release the ExoPlayer instance", source.getPlayer())
    }
}
