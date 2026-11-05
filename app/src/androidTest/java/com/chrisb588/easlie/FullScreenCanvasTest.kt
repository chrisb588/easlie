package com.chrisb588.easlie

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chrisb588.easlie.canvas.CanvasTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullScreenCanvasTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fullScreenBoardIsRendered() {
        composeRule
            .onNodeWithTag(CanvasTestTags.FullScreenBoard)
            .assertIsDisplayed()
            .assertContentDescriptionContains("Reference image board", substring = true)
        composeRule.onNodeWithText("Add images").assertIsEnabled()
    }
}
