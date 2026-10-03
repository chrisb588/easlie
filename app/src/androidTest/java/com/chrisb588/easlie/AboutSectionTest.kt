package com.chrisb588.easlie

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.ui.theme.Appearance
import com.chrisb588.easlie.ui.theme.EaslieTheme
import org.junit.Rule
import org.junit.Test

class AboutSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aboutShowsInstalledMetadataAndStaysUsableAcrossAppearanceChangesWithoutABoard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val installedVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val app = context.applicationContext as EaslieApplication
        val original = app.appearance.appearance
        try {
            compose.setContent { EaslieTheme { Column { AboutSection() } } }
            compose.onNodeWithText("About")
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
                .performClick()
            for (appearance in Appearance.entries) {
                compose.runOnIdle { app.appearance.select(appearance) }
                compose.onNodeWithText("easlie").assertIsDisplayed()
                compose.onNodeWithText("Version $installedVersion").assertIsDisplayed()
                compose.onNodeWithText("Close About")
                    .assertHasClickAction()
                    .assertHeightIsAtLeast(48.dp)
                    .assertWidthIsAtLeast(48.dp)
            }
            compose.onNodeWithText("Close About").performClick()
            compose.onNodeWithText("About").assertIsDisplayed().performClick()
            compose.onNodeWithText("Version $installedVersion").assertIsDisplayed()
        } finally {
            compose.runOnIdle { app.appearance.select(original) }
        }
    }
}
