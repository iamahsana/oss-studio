package com.iamahsana.ossstudio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.iamahsana.ossstudio.scene.persistence.SceneProjectStoreProvider
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
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
    fun createsProjectAndOpensItsEmptySceneCanvas() {
        composeRule.onNodeWithText("Project baru").performClick()
        composeRule.onNodeWithText("Nama project").performTextInput("OSS UI Smoke")
        composeRule.onNodeWithText("Buat").performClick()
        composeRule.onNodeWithText("OSS UI Smoke").assertIsDisplayed()
        composeRule.onNodeWithText("Canvas kosong").assertIsDisplayed()
    }

    @org.junit.After
    fun removeSmokeProject() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = SceneProjectStoreProvider.database(context)
        database.listRecent().filter { it.name == "OSS UI Smoke" }.forEach { database.delete(it.id) }
    }

}
