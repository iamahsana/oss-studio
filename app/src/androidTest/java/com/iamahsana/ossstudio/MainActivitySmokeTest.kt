package com.iamahsana.ossstudio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onRoot
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.iamahsana.ossstudio.stream.rtmp.RtmpDestinationProfileStore
import com.iamahsana.ossstudio.scene.persistence.SceneProjectStoreProvider
import com.iamahsana.ossstudio.scene.persistence.SceneAssetStorage
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
    private var smokeProfileName: String? = null
    private var createdSmokeProject = false
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndShowsHome() {
        composeRule.onNodeWithText("OSS Studio").assertIsDisplayed()
        composeRule.onNodeWithText("Your streaming workspace").assertIsDisplayed()
    }

    @Test
    fun bottomNavigationOpensEditorPage() {
        composeRule.onNodeWithText("Editor").performClick()
        composeRule.onNodeWithText("Scene Editor").assertIsDisplayed()
        composeRule.onNodeWithText("Build the scene that will be sent directly to LIVE.")
            .assertIsDisplayed()
    }
    @Test
    fun extensionCenterOpensAndShowsPackageInstallControls() {
        composeRule.onNodeWithText("Extensions").performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Pasang file .osspack").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Pasang file .osspack").assertIsDisplayed()
        composeRule.onNodeWithText("Belum ada ekstensi yang dipasang.").assertIsDisplayed()
    }

    @Test
    fun createsProjectAndOpensItsEmptySceneCanvas() {
        createdSmokeProject = true
        composeRule.onNodeWithText("Project baru").performClick()
        composeRule.onNodeWithText("Nama project").performTextInput("OSS UI Smoke")
        composeRule.onNodeWithText("Buat").performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("OSS UI Smoke").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("OSS UI Smoke").assertIsDisplayed()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Canvas kosong").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Canvas kosong").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun mixerPageShowsIndependentCoreChannelsAndBuses() {
        composeRule.onNodeWithText("Mixer").performClick()
        scrollUntilVisible("Mixer utama")
        composeRule.onNodeWithText("Mixer utama").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("LIVE master: 100%").assertIsDisplayed()
        composeRule.onNodeWithText("MONITOR master: 100%").assertIsDisplayed()
        composeRule.onNodeWithText("Microphone").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Internal / Game audio").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun studioSavesRtmpProfileWithoutDisplayingStreamKey() {
        val uniqueName = "OSS RTMP Smoke ${System.currentTimeMillis()}"
        smokeProfileName = uniqueName
        composeRule.onNodeWithText("Studio").performClick()
        composeRule.onNodeWithText("Tambah").performClick()
        composeRule.onNodeWithText("Nama tujuan").performTextInput(uniqueName)
        composeRule.onNodeWithText("Server URL (rtmp:// atau rtmps://)")
            .performTextInput("rtmps://live.example.com/app")
        composeRule.onNodeWithText("Stream key").performTextInput("private-smoke-key")
        composeRule.onNodeWithText("Simpan terenkripsi").performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText(uniqueName).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(uniqueName).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("private-smoke-key").fetchSemanticsNodes().isEmpty())
    }


    private fun scrollUntilVisible(text: String) {
        repeat(8) {
            if (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            composeRule.onRoot().performTouchInput { swipeUp() }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun removeOnlySmokeDestination() {
        val name = smokeProfileName ?: return
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching {
            val retained = RtmpDestinationProfileStore.load(context).filterNot { it.name == name }
            RtmpDestinationProfileStore.save(context, retained)
        }
    }

    @Test
    fun studioShowsLiveSessionControls() {
        composeRule.onNodeWithText("Studio").performClick()
        composeRule.onNodeWithText("LIVE control center").assertIsDisplayed()
        scrollUntilVisible("LIVE control")
        composeRule.onNodeWithText("LIVE control").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Mulai LIVE").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Hentikan").performScrollTo().assertIsDisplayed()
    }

    @After
    fun removeOnlySmokeProject() {
        if (!createdSmokeProject) return
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching {
            val database = SceneProjectStoreProvider.database(context)
            database.listRecent().filter { it.name == "OSS UI Smoke" }.forEach { summary ->
                database.get(summary.id)?.assets?.forEach { asset ->
                    runCatching { SceneAssetStorage.deleteAsset(context, asset) }
                }
                database.delete(summary.id)
            }
        }
    }

}
