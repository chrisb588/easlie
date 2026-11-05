package com.chrisb588.easlie

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.ui.theme.EaslieTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BoardRenameFeedbackTest {
    @get:Rule val rule = createComposeRule()

    @Test fun failedRenameShowsErrorAndKeepsSavedNameWithoutSuccessFeedback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "rename-feedback-${UUID.randomUUID()}")
        val legacy = File(root, "board")
        val board = rule.runOnIdle { BoardStore(legacy, collectionMigration = true) }
        try {
            rule.waitUntil(5000) { board.collectionReady }
            rule.runOnIdle { board.createBoard("Original") }
            rule.waitUntil(5000) { board.collectionReady && board.activeBoardId != null }
            val id = rule.runOnIdle { board.activeBoardId!! }
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

            renameTo("Saved")
            rule.waitUntil(5000) { board.message == "Board renamed to Saved." }
            rule.onNodeWithText("Board renamed to Saved.").assertIsDisplayed()
            val index = File(root, "boards.index")
            val before = index.readBytes()
            // A directory at the atomic-write path makes the real rename save fail.
            assertTrue(File(root, "boards.index.tmp").mkdir())

            renameTo("Unsaved")
            rule.waitUntil(5000) { board.message?.startsWith("Board could not be renamed:") == true }
            rule.onNodeWithText("Board could not be renamed:", substring = true).assertIsDisplayed()
            rule.onNodeWithText("Saved").assertIsDisplayed()
            rule.onNodeWithText("Unsaved").assertDoesNotExist()
            rule.onNodeWithText("Board renamed to", substring = true).assertDoesNotExist()
            rule.runOnIdle {
                assertEquals(id, board.activeBoardId)
                assertEquals(listOf(StoredBoard(id, "Saved")), board.boards)
            }
            assertArrayEquals(before, index.readBytes())
            assertEquals("Saved", BoardCollectionStorage(root, legacy).readCollection().boards.single().name)

            rule.onNodeWithText("Open board").performClick()
            rule.onNode(hasText("Saved") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            rule.onNodeWithText("Unsaved").assertDoesNotExist()
            rule.onNodeWithText("Close").performClick()
        } finally {
            rule.runOnIdle { board.releaseImages() }
            root.deleteRecursively()
        }
    }

    private fun renameTo(name: String) {
        rule.onNodeWithText("Open board").performClick()
        rule.onNodeWithText("Rename", substring = false).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(name)
        rule.onNodeWithText("Save", substring = false).performClick()
    }
}
