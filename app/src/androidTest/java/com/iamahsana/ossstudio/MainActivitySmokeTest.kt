package com.iamahsana.ossstudio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.iamahsana.ossstudio.stream.rtmp.RtmpDestinationProfileStore
import com.iamahsana.ossstudio.scene.persistence.SceneProjectStoreProvider
import com.iamahsana.ossstudio.scene.persistence.SceneProjectRepository
import com.iamahsana.ossstudio.scene.persistence.SceneAssetStorage
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        composeRule.onNodeWithText("Snap grid").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Grid").assertIsDisplayed()
        composeRule.onNodeWithText("Safe area").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Redo").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Pengaturan canvas").performScrollTo().performClick()
        composeRule.onNodeWithText("Portrait · 9:16 · 1080 × 1920").assertIsDisplayed()
        composeRule.onNodeWithText("Landscape · 16:9 · 1920 × 1080").performClick()

        composeRule.onNodeWithText("Tambah teks").performScrollTo().performClick()
        composeRule.onNodeWithTag("scene-resize-handle", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performScrollTo().performClick()
        assertTrue(composeRule.onAllNodesWithTag("scene-resize-handle", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Redo").performScrollTo().performClick()
        composeRule.onNodeWithText("Teks baru").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun deletingActiveProjectDoesNotLeaveStaleEditorProject() {
        createdSmokeProject = true
        composeRule.onNodeWithText("Project baru").performClick()
        composeRule.onNodeWithText("Nama project").performTextInput("OSS UI Smoke")
        composeRule.onNodeWithText("Buat").performClick()
        // ProjectHomeScreen invokes onOpenProject only after the database save succeeds.
        // Wait for the asynchronous repository callback, then assert the destination page.
        composeRule.waitUntil("newly created project opens Scene Editor", 15_000L) {
            composeRule.onAllNodesWithText("Scene Editor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Scene Editor").assertIsDisplayed()

        composeRule.onNodeWithText("Home").performClick()
        composeRule.waitUntil("saved project appears after returning Home", 15_000L) {
            composeRule.onAllNodesWithText("OSS UI Smoke").fetchSemanticsNodes().isNotEmpty()
        }
        val smokeContext = ApplicationProvider.getApplicationContext<Context>()
        val smokeProjectId = SceneProjectStoreProvider.database(smokeContext)
            .listRecent().first { it.name == "OSS UI Smoke" }.id
        composeRule.onNodeWithTag("delete-project-$smokeProjectId").performClick()
        composeRule.onNodeWithText("Hapus project?").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-delete-project").performClick()
        composeRule.waitUntil("delete dialog closes or reports a deletion error", 15_000L) {
            composeRule.onAllNodesWithText("Hapus project?").fetchSemanticsNodes().isEmpty() ||
                composeRule.onAllNodesWithText("Project tidak ditemukan.").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Project tidak dapat dibaca; file media tidak dihapus.").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Gagal menghapus project").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Hentikan atau selesaikan cleanup LIVE sebelum menghapus project aktif.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("Project tidak ditemukan.").assertCountEquals(0)
        composeRule.onAllNodesWithText("Gagal menghapus project").assertCountEquals(0)
        composeRule.waitUntil("target project is deleted from persistent storage", 15_000L) {
            SceneProjectStoreProvider.database(smokeContext).get(smokeProjectId) == null
        }
        composeRule.waitUntil("deleted project row disappears from Home", 15_000L) {
            composeRule.onAllNodesWithTag("delete-project-$smokeProjectId").fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithText("Editor").performClick()
        composeRule.onNodeWithText("Pilih atau buat project dari Home terlebih dahulu.").assertIsDisplayed()
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
            composeRule.onNodeWithTag("page-content").performTouchInput { swipeUp() }
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
        assertTrue(composeRule.onAllNodesWithText("Hentikan").fetchSemanticsNodes().isNotEmpty())
    }

    @After
    fun removeOnlySmokeProject() {
        if (!createdSmokeProject) return
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Leave the editor first so its close-time autosave is enqueued before cleanup.
        runCatching { composeRule.onNodeWithText("Home").performClick() }
        val repository = SceneProjectRepository(SceneProjectStoreProvider.database(context))
        val finished = CountDownLatch(1)
        repository.recent { result ->
            val targets = result.getOrNull().orEmpty().filter { it.name == "OSS UI Smoke" }
            if (targets.isEmpty()) {
                finished.countDown()
            } else {
                val remaining = AtomicInteger(targets.size)
                targets.forEach { summary ->
                    repository.load(summary.id) { loaded ->
                        loaded.getOrNull()?.assets?.forEach { asset ->
                            runCatching { SceneAssetStorage.deleteAsset(context, asset) }
                        }
                        repository.delete(summary.id) {
                            if (remaining.decrementAndGet() == 0) finished.countDown()
                        }
                    }
                }
            }
        }
        assertTrue("smoke project cleanup must finish after pending autosave", finished.await(10, TimeUnit.SECONDS))
        repository.close()
    }

}
