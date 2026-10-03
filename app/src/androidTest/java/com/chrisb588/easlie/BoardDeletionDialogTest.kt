package com.chrisb588.easlie

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chrisb588.easlie.canvas.BoardItem
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.canvas.CanvasPoint
import com.chrisb588.easlie.canvas.CanvasViewport
import com.chrisb588.easlie.ui.theme.EaslieTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI coverage compiled on the host; execution belongs to the lead's device run. */
class BoardDeletionDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun cancellingNamedConfirmationLeavesActiveContentAndViewportsUnchanged() {
        val root = File(rule.activity.cacheDir, "delete-dialog-${UUID.randomUUID()}")
        val resolver = rule.activity.contentResolver
        val provider = Uri.parse("content://com.chrisb588.easlie.test.images")
        val source = Uri.withAppendedPath(provider, "task-first.png")
        resolver.call(provider, "create-task-fixtures", null, null)
        lateinit var store: BoardStore
        try {
            rule.runOnUiThread { store = BoardStore(File(root, "board"), collectionMigration = true) }
            rule.waitUntil(5000) { store.collectionReady }
            rule.runOnUiThread { store.createBoard("Named references") }
            rule.waitUntil(5000) { store.activeBoardId != null }
            rule.runOnUiThread { store.resizeWindow(CanvasSize(600f, 400f)); store.enqueueImport(resolver, listOf(source)) }
            rule.waitUntil(5000) { !store.importing && store.items.size == 1 }
            lateinit var content: List<BoardItem>
            rule.runOnUiThread { content = store.items }
            val full = CanvasViewport(CanvasPoint(11f, 12f), 2f)
            val floating = CanvasViewport(CanvasPoint(21f, 22f), 3f)
            rule.runOnUiThread { store.viewport = full; store.setViewport(floating, true) }
            rule.waitUntil(5000) { store.viewport == full && store.viewportFor(true) == floating }
            lateinit var id: String
            rule.runOnUiThread { id = store.activeBoardId!!; store.save() }
            rule.setContent {
                EaslieTheme {
                    FloatingBoardScreen(store, {}, false, null, {}, {})
                }
            }
            rule.onNodeWithText("Open board").performClick()
            rule.onNodeWithText("Delete").performClick()
            rule.onNodeWithText("Delete Named references?").assertIsDisplayed()
            rule.onNodeWithText("This board and its images will be removed from easlie.").assertIsDisplayed()
            rule.onNodeWithText("Cancel").performClick()
            rule.runOnIdle {
                assertEquals(id, store.activeBoardId)
                assertEquals(listOf(id), store.boards.map { it.id })
                assertEquals(content, store.items)
                assertEquals(full, store.viewport)
                assertEquals(floating, store.viewportFor(true))
            }
            assertTrue(File(root, "boards/$id/board.json").isFile)
            rule.onNodeWithText("Open board").performClick()
            rule.onNodeWithText("Delete").performClick()
            rule.onNodeWithText("Delete").performClick()
            rule.waitUntil(5000) { store.activeBoardId == null && store.boards.isEmpty() }
            rule.onNodeWithText("Create board").assertIsDisplayed()
            rule.onNodeWithText("Create or open a board to continue.").assertIsDisplayed()
            assertFalse(File(root, "boards/$id").exists())
        } finally { resolver.delete(source, null, null); root.deleteRecursively() }
    }
}
