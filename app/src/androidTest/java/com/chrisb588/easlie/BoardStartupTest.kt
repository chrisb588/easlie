package com.chrisb588.easlie

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasTestTags
import com.chrisb588.easlie.ui.theme.EaslieTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BoardStartupTest {
    @get:Rule val rule = createComposeRule()

    @Test fun finishingStartupReplacesLoadingScreenAndEnablesBoardControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "startup-${UUID.randomUUID()}")
        val legacy = File(root, "board")
        val collection = BoardCollectionStorage(root, legacy).createBoard("Saved board")
        val indexBefore = File(root, "boards.index").readBytes()
        val cleanupStarted = CountDownLatch(1)
        val finishCleanup = CountDownLatch(1)
        val board = rule.runOnIdle {
            BoardStore(legacy, collectionMigration = true, discoverReconciliation = { storage ->
                cleanupStarted.countDown()
                check(finishCleanup.await(15, TimeUnit.SECONDS)) { "Startup test did not release cleanup" }
                storage.reconciliationCandidates()
            })
        }
        try {
            assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
            rule.setContent {
                EaslieTheme {
                    FloatingBoardScreen(
                        board = board,
                        onAddImages = {},
                        overlayPermissionGranted = false,
                        errorMessage = null,
                        onOpenOverlaySettings = {},
                        onStartFloatingBoard = {},
                    )
                }
            }
            rule.onNodeWithText("Opening board…").assertIsDisplayed()
            rule.onNodeWithText("Create board").assertIsNotEnabled()
            rule.onNodeWithText("Add images").assertIsNotEnabled()

            finishCleanup.countDown()
            rule.waitUntil(5000) { board.collectionReady }
            rule.onNodeWithText("Opening board…").assertDoesNotExist()
            rule.onNodeWithText("Saved board").assertIsDisplayed()
            rule.onNodeWithText("Create board").assertIsEnabled()
            rule.onNodeWithText("Open board").assertIsEnabled()
            rule.onNodeWithText("Add images").assertIsEnabled()
            rule.onNodeWithTag(CanvasTestTags.FullScreenBoard).assertIsDisplayed()
            rule.runOnIdle { assertEquals(collection.activeBoardId, board.activeBoardId) }
            org.junit.Assert.assertArrayEquals(indexBefore, File(root, "boards.index").readBytes())
        } finally {
            finishCleanup.countDown()
            rule.runOnIdle { board.releaseImages() }
            root.deleteRecursively()
        }
    }
}
