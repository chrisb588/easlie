package com.chrisb588.easlie

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.chrisb588.easlie.canvas.CanvasTestTags
import com.chrisb588.easlie.ui.theme.EaslieTheme
import java.io.File
import java.util.UUID
import org.junit.Rule
import org.junit.Test

class FullScreenCanvasTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun fullScreenBoardIsRendered() {
        val root = File(rule.activity.cacheDir, "full-screen-${UUID.randomUUID()}")
        val collection = BoardCollectionStorage(root, File(root, "board"))
        collection.createBoard("Canvas fixture")
        val board = rule.runOnIdle { BoardStore(File(root, "board"), collectionMigration = true) }
        try {
            rule.waitUntil(5000) { board.collectionReady }
            rule.setContent { EaslieTheme { FloatingBoardScreen(board, {}, false, null, {}, {}) } }
            rule.onNodeWithTag(CanvasTestTags.FullScreenBoard).assertIsDisplayed()
                .assertContentDescriptionContains("Reference image board", substring = true)
            rule.onNodeWithContentDescription("Add images").assertIsEnabled()
        } finally {
            rule.runOnIdle { board.releaseImages() }
            root.deleteRecursively()
        }
    }
}
