package com.chrisb588.easlie

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.ui.theme.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppearanceSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun missingPreferenceDefaultsAndSelectionSurvivesStoreRecreationAndBoardSwitches() {
        val name = "appearance-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val root = File(context.cacheDir, name)
        try {
            val appearance = AppearanceStore(prefs)
            assertEquals(Appearance.SYSTEM, appearance.appearance)
            appearance.select(Appearance.DARK)
            val collection = BoardCollectionStorage(root, File(root, "board"))
            collection.initialize()
            val first = collection.createBoard("First").activeBoardId!!
            collection.createBoard("Second")
            collection.openBoard(first)
            assertEquals(Appearance.DARK, AppearanceStore(prefs).appearance)
            appearance.select(Appearance.LIGHT)
            assertEquals(Appearance.LIGHT, AppearanceStore(prefs).appearance)
            appearance.select(Appearance.SYSTEM)
            assertEquals(Appearance.SYSTEM, AppearanceStore(prefs).appearance)
        } finally { prefs.edit().clear().commit(); root.deleteRecursively() }
    }

    @Test fun overlayPanelOffersEveryChoiceWithoutCreatingAnotherWindow() {
        val name = "appearance-panel-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val store = AppearanceStore(prefs)
        try {
            compose.setContent { EaslieTheme { Column { AppearanceSettings(store, floatingMode = true) } } }
            for ((label, choice) in listOf("Light" to Appearance.LIGHT,
                "Dark" to Appearance.DARK, "Use system default" to Appearance.SYSTEM)) {
                compose.onNodeWithText("Appearance").performClick()
                compose.onNodeWithText(label).performClick()
                compose.runOnIdle { assertEquals(choice, store.appearance) }
            }
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun sharedHostThemeUpdatesLiveForPreferenceAndSystemChanges() {
        val app = context.applicationContext as EaslieApplication
        val original = app.appearance.appearance
        var systemDark by mutableStateOf(false)
        val observed = arrayOf(Color.Unspecified, Color.Unspecified)
        try {
            compose.runOnUiThread { app.appearance.select(Appearance.SYSTEM) }
            compose.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    Column {
                        repeat(2) { host ->
                            EaslieTheme(dynamicColor = false) {
                                val background = MaterialTheme.colorScheme.background
                                SideEffect { observed[host] = background }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            val light = observed[0]
            compose.runOnIdle { systemDark = true }
            compose.waitForIdle()
            val dark = observed[0]
            assertNotEquals(light, dark)
            assertEquals(dark, observed[1])
            compose.runOnIdle { app.appearance.select(Appearance.LIGHT) }
            compose.waitForIdle()
            assertEquals(light, observed[0]); assertEquals(light, observed[1])
            compose.runOnIdle { systemDark = false; app.appearance.select(Appearance.DARK) }
            compose.waitForIdle()
            assertEquals(dark, observed[0]); assertEquals(dark, observed[1])
            compose.runOnIdle { app.appearance.select(Appearance.SYSTEM) }
            compose.waitForIdle()
            assertEquals(light, observed[0]); assertEquals(light, observed[1])
        } finally { compose.runOnUiThread { app.appearance.select(original) } }
    }
}
