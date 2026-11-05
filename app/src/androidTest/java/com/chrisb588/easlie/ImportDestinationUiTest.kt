package com.chrisb588.easlie

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.chrisb588.easlie.images.sharedImageUris
import com.chrisb588.easlie.ui.theme.EaslieTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Paused source reads make board changes during import deterministic. */
class ImportDestinationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val resolver get() = rule.activity.contentResolver
    private val provider = Uri.parse("content://com.chrisb588.easlie.test.images")
    private val first = Uri.withAppendedPath(provider, "task-first.png")
    private val second = Uri.withAppendedPath(provider, "task-second.png")
    private lateinit var root: File
    private lateinit var store: BoardStore
    private lateinit var destination: String
    private lateinit var other: String

    @Before fun prepareBoards() {
        resolver.call(provider, "create-task-fixtures", null, null)
        root = File(rule.activity.cacheDir, "destination-ui-${UUID.randomUUID()}")
        val collection = BoardCollectionStorage(root, File(root, "board"))
        collection.initialize()
        destination = collection.createBoard("Destination").activeBoardId!!
        other = collection.createBoard("Other").activeBoardId!!
        collection.openBoard(destination)
        rule.runOnUiThread { store = BoardStore(File(root, "board"), collectionMigration = true) }
        rule.waitUntil(5000) { store.collectionReady }
        rule.setContent { EaslieTheme { FloatingBoardScreen(store, {}, false, null, {}, {}) } }
        rule.waitUntil(5000) { store.windowSize.width > 0f }
    }

    @After fun cleanup() {
        resolver.call(provider, "release-reads", null, null)
        if (::store.isInitialized) {
            rule.waitUntil(5000) { !store.importing }
            rule.runOnUiThread { store.releaseImages() }
        }
        listOf(first, second).forEach { resolver.delete(it, null, null) }
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun snapshot(id: String) = BoardStorage(File(root, "boards/$id")).load().snapshot

    private fun pauseImport(uris: List<Uri>) {
        resolver.call(provider, "hold-reads", null, null)
        rule.runOnUiThread { store.enqueueImport(resolver, uris) }
        rule.waitUntil(5000) { resolver.call(provider, "read-started", null, null)?.getBoolean("started") == true }
        rule.runOnIdle { assertTrue(store.importing) }
    }

    private fun openOther() {
        rule.onNodeWithText("Open board").performClick()
        rule.onNodeWithText("Other").performClick()
        rule.waitUntil(5000) { store.collectionReady && store.activeBoardId == other }
    }

    private fun deleteDestination() {
        rule.onNodeWithText("Open board").performClick()
        // Destination is first in recents; the confirmation names it before deletion.
        rule.onAllNodesWithText("Delete")[0].performClick()
        rule.onNodeWithText("Delete Destination?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(5000) { store.boards.none { it.id == destination } }
    }

    @Test fun pickerResultKeepsDestinationThroughRenameSwitchAndRetry() = verifyRouting(shared = false)

    @Test fun sharedPayloadKeepsDestinationThroughRenameSwitchAndRetry() = verifyRouting(shared = true)

    private fun verifyRouting(shared: Boolean) {
        resolver.call(provider, "fail-next-read", second.lastPathSegment, null)
        val uris = if (shared) sharedImageUris(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))) else listOf(first, second)
        pauseImport(uris)
        rule.onNodeWithText("Open board").performClick()
        rule.onAllNodesWithText("Rename")[0].performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("Renamed destination")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5000) { store.boards.first { it.id == destination }.name == "Renamed destination" }
        openOther()
        resolver.call(provider, "release-reads", null, null)
        rule.waitUntil(5000) { !store.importing && store.canRetryImport }
        val committed = snapshot(destination).items.single()
        assertTrue(snapshot(other).items.isEmpty())
        rule.onNodeWithText("Retry failed images").performClick()
        rule.waitUntil(5000) { !store.importing && !store.canRetryImport && snapshot(destination).items.size == 2 }
        rule.onNodeWithText("Retry failed images").assertDoesNotExist()
        assertEquals(committed, snapshot(destination).items.first())
        assertTrue(snapshot(other).items.isEmpty())
        rule.runOnIdle { assertEquals(other, store.activeBoardId); assertTrue(store.items.isEmpty()) }
        rule.onNodeWithText("Open board").performClick()
        rule.onNodeWithText("Renamed destination").performClick()
        rule.waitUntil(5000) { store.collectionReady && store.activeBoardId == destination && store.items.size == 2 }
        rule.onNodeWithText("Renamed destination").assertIsDisplayed()
    }

    @Test fun deletingDestinationDuringCopyExplainsFailureWithoutRedirecting() {
        pauseImport(listOf(first, second))
        deleteDestination()
        openOther()
        resolver.call(provider, "release-reads", null, null)
        rule.waitUntil(5000) { !store.importing && store.message?.contains("cannot continue or be retried") == true }
        assertDeletedDestination()
    }

    @Test fun deletingDestinationBeforeRetryRemovesRetryAndExplainsFailure() {
        resolver.call(provider, "fail-next-read", first.lastPathSegment, null)
        rule.runOnUiThread { store.enqueueImport(resolver, listOf(first)) }
        rule.waitUntil(5000) { !store.importing && store.canRetryImport }
        rule.onNodeWithText("Retry failed images").assertIsDisplayed()
        deleteDestination()
        openOther()
        assertDeletedDestination()
        rule.runOnUiThread { store.retryImport() }
        rule.runOnIdle { assertFalse(store.importing); assertTrue(store.items.isEmpty()) }
        assertFalse(File(root, "boards/$destination").exists())
    }

    private fun assertDeletedDestination() {
        rule.onNodeWithText("cannot continue or be retried", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Retry failed images").assertDoesNotExist()
        rule.runOnIdle { assertEquals(other, store.activeBoardId); assertTrue(store.items.isEmpty()) }
        assertFalse(File(root, "boards/$destination").exists())
        assertTrue(snapshot(other).items.isEmpty())
    }
}
