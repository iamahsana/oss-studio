package com.iamahsana.ossstudio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
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
        composeRule.onNodeWithText("← Home").assertIsDisplayed()
        composeRule.onNodeWithTag("editor-workspace").assertIsDisplayed()
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
        composeRule.onAllNodesWithText("OSS UI Smoke").assertCountEquals(2)
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Canvas kosong").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Canvas kosong").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("scene-element-insertion-shelf").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("scene-add-media").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-add-share-screen").assertIsDisplayed()
        composeRule.onNodeWithText("Snap grid").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Grid").assertIsDisplayed()
        composeRule.onNodeWithText("Safe area").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Redo").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Pengaturan canvas").performScrollTo().performClick()
        composeRule.onNodeWithText("Format canvas").assertIsDisplayed()
        composeRule.onNodeWithText("Rasio dan ukuran canvas mengikuti orientasi output LIVE project.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Tutup").performClick()

        composeRule.onNodeWithTag("scene-add-text").performScrollTo().performClick()
        // The layer row displays the source name ("Text"), not its content
        // ("Teks baru"). Wait for the tagged layer row before checking canvas.
        composeRule.waitUntil("new text source appears in the scene layers", 15_000L) {
            composeRule.onAllNodesWithTag("scene-element-item").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("scene-panel-layers").performScrollTo().assertIsDisplayed()
        composeRule.waitUntil("new text element is composed on the canvas", 15_000L) {
            composeRule.onAllNodesWithTag("scene-canvas-element", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Select from the layer list explicitly so the test validates resize-handle
        // visibility for a selected element, independent of add-action focus timing.
        composeRule.onAllNodesWithTag("scene-canvas-element", useUnmergedTree = true)[0].performClick()
        composeRule.onNodeWithTag("scene-resize-handle", useUnmergedTree = true).performScrollTo().assertExists().assertIsDisplayed()
        composeRule.onNodeWithTag("scene-panel-properties").performScrollTo().performClick()
        composeRule.onNodeWithTag("scene-properties-panel").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("scene-panel-layers").performScrollTo().performClick()
        composeRule.onNodeWithText("Undo").performScrollTo().performClick()
        assertTrue("Undo should remove the resize handle when the new element is removed", composeRule.onAllNodesWithTag("scene-resize-handle", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Redo").performScrollTo().performClick()
        composeRule.onNodeWithText("Teks baru").performScrollTo().assertIsDisplayed()
        // Undo clears the selection when the element is absent; wait for redo to recompose it.
        composeRule.waitUntil("redo restores the canvas element", 10_000L) {
            composeRule.onAllNodesWithTag("scene-canvas-element", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("scene-canvas-element", useUnmergedTree = true)[0].performClick()
        composeRule.onNodeWithTag("scene-resize-handle", useUnmergedTree = true).performScrollTo().assertExists().assertIsDisplayed()

        composeRule.onNodeWithText("Tambah teks").performScrollTo().performClick()
        composeRule.onNodeWithTag("scene-multiselect-toggle").performScrollTo().assertIsDisplayed().performClick()
        // Verify the mode itself before testing multi-selection state.
        composeRule.onNodeWithText("Selesai memilih").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-multiselect-status").performScrollTo().assertExists().assertIsDisplayed()
        composeRule.onNodeWithText("Mode pemilihan aktif · Dipilih: 0").assertIsDisplayed()
        assertTrue("Multi-select mode should show exactly two scene layer rows", composeRule.onAllNodesWithTag("scene-element-item").fetchSemanticsNodes().size == 2)
        composeRule.onAllNodesWithTag("scene-element-item")[0].performScrollTo().performClick()
        composeRule.onAllNodesWithTag("scene-element-item")[1].performScrollTo().performClick()
        assertTrue("Selecting both layer rows should mark both as selected", composeRule.onAllNodesWithText("✓ Text").fetchSemanticsNodes().size == 2)
        composeRule.onNodeWithText("Kelompokkan (2)").performScrollTo().performClick()
        composeRule.onNodeWithText("Pisahkan grup").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Pisahkan grup").performClick()
        assertTrue("Ungrouping should remove the split-group action", composeRule.onAllNodesWithText("Pisahkan grup").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun settingsPageShowsAppearanceStorageAndDiagnosticExport() {
        composeRule.onNodeWithTag("nav-settings").performClick()
        composeRule.onNodeWithText("Tema aplikasi").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Warna aksen").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Penyimpanan aplikasi").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ekspor log diagnostik").performScrollTo().assertIsDisplayed()
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



    @Test
    fun editorCanvasSettingsExplainsLiveOrientationSourceOfTruth() {
        createdSmokeProject = true
        composeRule.onNodeWithText("Project baru").performClick()
        composeRule.onNodeWithText("Nama project").performTextInput("OSS UI Smoke")
        composeRule.onNodeWithText("Buat").performClick()
        composeRule.waitUntil("new project opens its editor", 15_000L) {
            composeRule.onAllNodesWithText("Canvas kosong").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Pengaturan canvas").performScrollTo().performClick()
        composeRule.onNodeWithText("Format canvas").assertIsDisplayed()
        composeRule.onNodeWithText("Untuk mengubahnya, buka halaman Studio lalu pilih Portrait 9:16 atau Landscape 16:9 pada bagian Orientasi output LIVE.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Tutup").performClick()
    }

    @Test
    fun activeProjectIsRestoredAfterActivityRecreation() {
        createdSmokeProject = true
        composeRule.onNodeWithText("Project baru").performClick()
        composeRule.onNodeWithText("Nama project").performTextInput("OSS UI Smoke")
        composeRule.onNodeWithText("Buat").performClick()
        composeRule.waitUntil("new project opens its editor", 15_000L) {
            composeRule.onAllNodesWithText("Canvas kosong").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil("saved active project reloads after Activity recreation", 15_000L) {
            composeRule.onAllNodesWithText("Canvas kosong").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Scene Editor").assertIsDisplayed()
        composeRule.onNodeWithText("Canvas kosong").performScrollTo().assertIsDisplayed()
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

        composeRule.onNodeWithText("← Home").performClick()
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
    fun studioOrientationChangeConvertsProjectAndEditorCanvasTogether() {
        createdSmokeProject = true
        composeRule.onNodeWithText("Project baru").performClick()
        composeRule.onNodeWithText("Nama project").performTextInput("OSS UI Smoke")
        composeRule.onNodeWithText("Buat").performClick()
        composeRule.waitUntil("new project opens editor", 15_000L) {
            composeRule.onAllNodesWithText("Canvas kosong").fetchSemanticsNodes().isNotEmpty()
        }

        // Scene Editor is a dedicated workspace; return Home before using bottom navigation.
        composeRule.onNodeWithText("← Home").performClick()
        composeRule.onNodeWithText("Studio").performClick()
        scrollUntilVisible("LIVE control")
        composeRule.onNodeWithText("Landscape 16:9").performScrollTo().performClick()
        composeRule.onNodeWithText("Ubah orientasi project?").assertIsDisplayed()
        composeRule.onNodeWithText("Konversi project").performClick()
        composeRule.waitUntil("orientation conversion completes", 15_000L) {
            composeRule.onAllNodesWithText("Orientasi project berhasil diubah ke Landscape 16:9.")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Editor").performClick()
        composeRule.waitUntil("editor receives converted project", 15_000L) {
            composeRule.onAllNodesWithTag("scene-canvas-dimensions").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("scene-canvas-dimensions").assert(hasText("1920 × 1080 px"))
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