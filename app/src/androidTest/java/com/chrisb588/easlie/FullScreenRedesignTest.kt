package com.chrisb588.easlie

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.chrisb588.easlie.canvas.CanvasPoint
import com.chrisb588.easlie.canvas.CanvasTestTags
import com.chrisb588.easlie.canvas.CanvasViewport
import com.chrisb588.easlie.ui.theme.EaslieTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** All content belongs to cache fixtures; these tests never inspect the user's boards. */
class FullScreenRedesignTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var board: BoardStore
    private var floatingStarts = 0
    private var settingsOpens = 0
    private val provider = Uri.parse("content://com.chrisb588.easlie.test.images")

    @Before fun prepare() {
        root = File(rule.activity.cacheDir, "redesign-${UUID.randomUUID()}")
        board = rule.runOnIdle { BoardStore(File(root, "board"), collectionMigration = true) }
        rule.waitUntil(5000) { board.collectionReady }
    }

    @After fun clean() {
        rule.runOnIdle { board.releaseImages() }
        root.deleteRecursively()
    }

    private fun screen(granted: Boolean = false) {
        rule.setContent { EaslieTheme {
            Box(Modifier.fillMaxSize().testTag("host-bounds")) {
                FloatingBoardScreen(board, {}, granted, null, { settingsOpens++ }, { floatingStarts++ })
            }
        } }
    }

    private fun create(name: String): String {
        rule.runOnIdle { board.createBoard(name) }
        rule.waitUntil(5000) { board.collectionReady && board.boards.any { it.name == name && it.id == board.activeBoardId } }
        return rule.runOnIdle { board.activeBoardId!! }
    }

    @Test fun emptyStartupCreatesBoardAndHomePreservesIdentityAndViewportsAcrossRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { EaslieTheme { FloatingBoardScreen(board, {}, false, null, {}, {}) } }
        rule.onNodeWithText("Your boards").assertIsDisplayed()
        rule.onNodeWithContentDescription("Create board").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("My references")
        rule.onNodeWithText("Create", substring = false).performClick()
        rule.waitUntil(5000) { board.activeBoardId != null && board.collectionReady }
        rule.onNodeWithTag(CanvasTestTags.FullScreenBoard).assertIsDisplayed()
        val id = rule.runOnIdle { board.activeBoardId }
        val full = CanvasViewport(CanvasPoint(15f, 30f), 2f)
        val floating = CanvasViewport(CanvasPoint(-5f, 20f), 3f)
        rule.runOnIdle { board.viewport = full; board.setViewport(floating, true) }
        rule.onNodeWithContentDescription("Home").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Your boards").assertIsDisplayed()
        rule.onNodeWithTag(CanvasTestTags.FullScreenBoard).assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(id, board.activeBoardId)
            assertEquals(full, board.viewport)
            assertEquals(floating, board.viewportFor(true))
        }
        rule.onNodeWithText("My references").performClick()
        rule.onNodeWithTag(CanvasTestTags.FullScreenBoard).assertIsDisplayed()
    }

    @Test fun savedActiveBoardUsesEntireHostAndMenusDoNotPanCanvas() {
        val first = create("First")
        create("Second")
        screen()
        val canvas = rule.onNodeWithTag(CanvasTestTags.FullScreenBoard).fetchSemanticsNode().boundsInRoot
        val host = rule.onNodeWithTag("host-bounds").fetchSemanticsNode().boundsInRoot
        assertEquals(host, canvas)
        val before = rule.runOnIdle { board.viewport }
        rule.onNodeWithContentDescription("Open board").performClick()
        rule.onNodeWithText("First").performClick()
        rule.waitUntil(5000) { board.collectionReady && board.activeBoardId == first }
        rule.onNodeWithContentDescription("Actions").performClick()
        rule.onNodeWithText("Deselect").performClick()
        rule.runOnIdle { assertEquals(before, board.viewport) }
        rule.onNodeWithContentDescription("Home").performClick()
        rule.openBoardActions("Second")
        rule.onNodeWithText("Rename").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("Renamed")
        rule.onNodeWithText("Rename").performClick()
        rule.waitUntil(5000) { board.boards.any { it.name == "Renamed" } }
        rule.onNodeWithText("Renamed").assertIsDisplayed()
    }

    @Test fun deniedOverlayShowsContextualGuidanceAndCancelLeavesCanvasUsable() {
        create("Permission fixture")
        screen()
        rule.onNodeWithContentDescription("Actions").performClick()
        rule.onNodeWithText("Switch to floating mode").performClick()
        rule.onNodeWithText("Allow floating mode").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Add images").assertIsEnabled()
        rule.runOnIdle { assertEquals(0, floatingStarts); assertEquals(0, settingsOpens) }
        rule.onNodeWithContentDescription("Actions").performClick()
        rule.onNodeWithText("Switch to floating mode").performClick()
        rule.onNodeWithText("Open overlay settings").performClick()
        rule.runOnIdle { assertEquals(1, settingsOpens); assertEquals(0, floatingStarts) }
    }

    @Test fun grantedOverlayUsesExistingModeEntryAndThemeAndAboutAreInActions() {
        create("Entry fixture")
        screen(granted = true)
        rule.onNodeWithContentDescription("Actions").performClick()
        rule.onNodeWithText("Change theme").performClick()
        rule.onNodeWithText("Use system default").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithContentDescription("Actions").performClick()
        rule.onNodeWithText("About").performClick()
        rule.onNodeWithText("Version: v${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithContentDescription("Actions").performClick()
        rule.onNodeWithText("Switch to floating mode").performClick()
        rule.runOnIdle { assertEquals(1, floatingStarts); assertEquals(0, settingsOpens) }
    }

    @Test fun messagesStackExpireIndependentlyAndRepeatedErrorsAppearAgain() {
        create("Messages fixture")
        screen()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { board.message = "First warning" }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { board.message = "Second error" }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("First warning").assertIsDisplayed()
        rule.onNodeWithText("Second error").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(4500)
        rule.onNodeWithText("First warning").assertDoesNotExist()
        rule.onNodeWithText("Second error").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(6000)
        rule.onNodeWithText("Second error").assertDoesNotExist()
        rule.runOnIdle { board.message = "Second error" }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Second error").assertIsDisplayed()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        rule.onNodeWithText("Second error").assertDoesNotExist()
    }

    @Test fun unrelatedRenameDoesNotDuplicateImportRetryAndDismissalKeepsRetryAvailable() {
        create("Retry fixture")
        val resolver = rule.activity.contentResolver
        resolver.call(provider, "create-task-fixtures", null, null)
        resolver.call(provider, "fail-next-read", "task-first.png", null)
        screen()
        rule.waitUntil(5000) { board.windowSize.width > 0f }
        rule.runOnIdle { board.enqueueImport(resolver, listOf(Uri.withAppendedPath(provider, "task-first.png"))) }
        rule.waitUntil(5000) { !board.importing && board.canRetryImport }
        rule.runOnIdle { board.renameBoard(board.activeBoardId!!, "Renamed retry fixture") }
        rule.waitUntil(5000) { board.boards.single().name == "Renamed retry fixture" }
        rule.onAllNodesWithContentDescription("Retry failed images").assertCountEquals(1)
        // Newest success is first; the older import failure is last.
        rule.onAllNodesWithContentDescription("Dismiss").onLast().performClick()
        rule.onAllNodesWithContentDescription("Retry failed images").assertCountEquals(1)
    }

    @Test fun migrationRecoveryRemainsAvailableAfterDismissalAndSuccessfulRetry() {
        val legacy = File(root, "blocked/board").apply { mkdirs() }
        val orphan = File(legacy, "orphan").apply { writeText("original bytes") }
        val failed = rule.runOnIdle { BoardStore(legacy, collectionMigration = true) }
        rule.waitUntil(5000) { failed.migrationFailed }
        rule.setContent { EaslieTheme { FloatingBoardScreen(failed, {}, false, null, {}, {}) } }
        rule.mainClock.advanceTimeBy(6000)
        rule.onNodeWithContentDescription("Retry").assertIsDisplayed()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        rule.onNodeWithContentDescription("Retry").assertIsDisplayed()
        assertEquals("original bytes", orphan.readText())
        orphan.delete()
        rule.onNodeWithContentDescription("Retry").performClick()
        rule.waitUntil(5000) { failed.collectionReady }
        rule.onNodeWithContentDescription("Retry").assertDoesNotExist()
        rule.onNodeWithContentDescription("Create board").assertIsEnabled()
        rule.runOnIdle { failed.releaseImages() }
    }

    @Test fun menuDeselectWorksWhenImageCoversTheCanvasAndPreviewDoesNotSwitchBoards() {
        create("Selection fixture")
        val resolver = rule.activity.contentResolver
        resolver.call(provider, "create-task-fixtures", null, null)
        screen()
        rule.waitUntil(5000) { board.windowSize.width > 0f }
        rule.runOnIdle { board.enqueueImport(resolver, listOf(Uri.withAppendedPath(provider, "task-first.png"))) }
        rule.waitUntil(5000) { !board.importing && board.items.size == 1 }
        rule.runOnIdle {
            board.viewport = CanvasViewport()
            board.update(board.items.single().copy(center = CanvasPoint(0f, 0f), width = board.windowSize.width * 3, height = board.windowSize.height * 3))
        }
        val original = rule.runOnIdle { board.items.single() }
        rule.onNodeWithTag(CanvasTestTags.FullScreenBoard).performTouchInput { click(center) }
        rule.onNodeWithContentDescription("Actions").performClick()
        rule.onNodeWithText("Deselect").performClick()
        rule.onNodeWithTag(CanvasTestTags.FullScreenBoard).performTouchInput { swipe(center, center + Offset(60f, 40f)) }
        rule.runOnIdle {
            assertEquals(original, board.items.single())
            assertNotEquals(CanvasViewport(), board.viewport)
        }
        val id = rule.runOnIdle { board.activeBoardId }
        val viewport = rule.runOnIdle { board.viewport }
        rule.onNodeWithContentDescription("Home").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithContentDescription("Preview of Selection fixture").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { assertEquals(id, board.activeBoardId); assertEquals(viewport, board.viewport) }
    }
}
