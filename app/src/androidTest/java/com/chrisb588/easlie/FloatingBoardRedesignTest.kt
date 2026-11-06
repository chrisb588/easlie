package com.chrisb588.easlie

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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

/** Cache-owned boards and synthetic images only. No user board state is touched. */
class FloatingBoardRedesignTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var board: BoardStore
    private var returned = 0
    private var closed = 0
    private var imports = 0
    private var creates = 0
    private val controlsVisible = mutableStateOf(true)
    private val provider = Uri.parse("content://com.chrisb588.easlie.test.images")

    @Before fun prepare() {
        root = File(rule.activity.cacheDir, "floating-redesign-${UUID.randomUUID()}")
        board = rule.runOnIdle { BoardStore(File(root, "board"), collectionMigration = true) }
        rule.waitUntil(5000) { board.collectionReady }
        create("Floating fixture")
    }
    @After fun clean() {
        rule.runOnIdle { board.releaseImages() }
        root.deleteRecursively()
    }
    private fun create(name: String): String {
        rule.runOnIdle { board.createBoard(name) }
        rule.waitUntil(5000) { board.collectionReady && board.boards.any { it.name == name && it.id == board.activeBoardId } }
        return rule.runOnIdle { board.activeBoardId!! }
    }
    private fun host(width: Int = 280, height: Int = 248) {
        rule.setContent { EaslieTheme {
            Box(Modifier.size(width.dp, height.dp)) {
                FloatingBoardUi(board, { returned++ }, { closed++ }, { imports++ }, { creates++ },
                    controlsVisible = controlsVisible.value)
            }
        } }
    }
    @Test fun minimumSizeHasDistinctControlsAndMenusDoNotChangeViewports() {
        val first = rule.runOnIdle { board.activeBoardId }
        create("Other fixture")
        host()
        val before = rule.runOnIdle { board.viewportFor(true) }
        val canvas = rule.onNodeWithTag(CanvasTestTags.FloatingBoard).fetchSemanticsNode().boundsInRoot
        assertEquals(rule.onNodeWithTag("floating-host").fetchSemanticsNode().boundsInRoot, canvas)
        val returns = rule.onNodeWithContentDescription("Return to full-screen mode")
        val close = rule.onNodeWithContentDescription("Close floating board")
        val add = rule.onNodeWithContentDescription("Add images")
        returns.assertIsDisplayed().assertTouchHeightIsEqualTo(56.dp)
        close.assertIsDisplayed().assertTouchWidthIsEqualTo(56.dp)
        add.assertIsDisplayed().assertTouchHeightIsEqualTo(56.dp)
        assertTrue(returns.fetchSemanticsNode().boundsInRoot.right <= close.fetchSemanticsNode().boundsInRoot.left)
        assertTrue(close.fetchSemanticsNode().boundsInRoot.bottom <= add.fetchSemanticsNode().boundsInRoot.top)
        rule.onNodeWithContentDescription("Open board").performClick()
        rule.onNodeWithText("Floating fixture").performClick()
        rule.waitUntil(5000) { board.collectionReady && board.activeBoardId == first }
        rule.onNodeWithContentDescription("Open board").performClick()
        rule.onNodeWithText("New board").performClick()
        add.performClick(); returns.performClick(); close.performClick()
        rule.runOnIdle {
            assertEquals(before, board.viewportFor(true))
            assertEquals(1, creates); assertEquals(1, imports); assertEquals(1, returned); assertEquals(1, closed)
        }
    }
    @Test fun noticesAndDeselectStayClearOfControlsAtMinimumSize() {
        host()
        val resolver = rule.activity.contentResolver
        resolver.call(provider, "create-task-fixtures", null, null)
        rule.waitUntil(5000) { board.windowSize.width > 0f }
        rule.runOnIdle {
            board.enqueueImport(resolver, listOf(Uri.withAppendedPath(provider, "task-first.png")), floatingMode = true)
        }
        rule.waitUntil(5000) { !board.importing && board.items.size == 1 }
        rule.runOnIdle {
            board.update(board.items.single().copy(center = CanvasPoint(0f, 0f), width = 2000f, height = 2000f))
        }
        val image = rule.runOnIdle { board.items.single() }
        rule.onNodeWithTag(CanvasTestTags.FloatingBoard).performTouchInput { click(center) }
        rule.onNodeWithTag(CanvasTestTags.Deselect).assertIsDisplayed()
        rule.runOnIdle { board.message = "Fixture warning" }
        val warning = rule.onNodeWithText("Fixture warning").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val selector = rule.onNodeWithContentDescription("Open board").fetchSemanticsNode().boundsInRoot
        val deselect = rule.onNodeWithTag(CanvasTestTags.Deselect).fetchSemanticsNode().boundsInRoot
        assertTrue(warning.top >= selector.bottom)
        assertTrue(warning.bottom <= deselect.top)
        val canvas = rule.onNodeWithTag(CanvasTestTags.FloatingBoard).fetchSemanticsNode().boundsInRoot
        assertEquals(canvas.center.x, deselect.center.x, 1f)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { controlsVisible.value = false }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("Open board").assertDoesNotExist()
        rule.onNodeWithContentDescription("Return to full-screen mode").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close floating board").assertDoesNotExist()
        rule.onNodeWithContentDescription("Add images").assertDoesNotExist()
        rule.onNodeWithText("Fixture warning").assertIsDisplayed()
        rule.onNodeWithTag(CanvasTestTags.Deselect).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(6500)
        rule.onNodeWithText("Fixture warning").assertDoesNotExist()
        rule.onNodeWithTag(CanvasTestTags.Deselect).performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(CanvasTestTags.Deselect).assertDoesNotExist()
        rule.runOnIdle { assertEquals(image, board.items.single()) }
    }
    @Test fun persistentImportRetrySurvivesHiddenControlsAndNoticeExpiry() {
        host()
        val resolver = rule.activity.contentResolver
        resolver.call(provider, "create-task-fixtures", null, null)
        resolver.call(provider, "fail-next-read", "task-first.png", null)
        rule.waitUntil(5000) { board.windowSize.width > 0f }
        rule.runOnIdle {
            board.enqueueImport(resolver, listOf(Uri.withAppendedPath(provider, "task-first.png")), floatingMode = true)
        }
        rule.waitUntil(5000) { !board.importing && board.canRetryImport }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { controlsVisible.value = false }
        rule.mainClock.advanceTimeBy(6500)
        rule.onNodeWithContentDescription("Close floating board").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithContentDescription("Retry failed images").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(5000) { !board.importing && board.items.size == 1 && !board.canRetryImport }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription("Retry failed images").assertDoesNotExist()
    }
    @Test fun floatingImportUsesFloatingViewportAndLeavesFullscreenViewportUntouched() {
        host(360, 300)
        val resolver = rule.activity.contentResolver
        resolver.call(provider, "create-task-fixtures", null, null)
        val full = CanvasViewport(CanvasPoint(-2000f, -3000f), 2f)
        val floating = CanvasViewport(CanvasPoint(4000f, 5000f), 1f)
        rule.waitUntil(5000) { board.windowSize.width > 0f }
        rule.runOnIdle {
            board.viewport = full
            board.setViewport(floating, true)
            board.enqueueImport(resolver, listOf(Uri.withAppendedPath(provider, "task-first.png")), floatingMode = true)
        }
        rule.waitUntil(5000) { !board.importing && board.items.size == 1 }
        rule.runOnIdle {
            assertEquals(full, board.viewport)
            assertEquals(floating, board.viewportFor(true))
            assertEquals(floating.center, board.items.single().center)
        }
    }
    @Test fun portraitLandscapeAndLiveThemesKeepControlNamesAndPlacement() {
        val portrait = mutableStateOf(true)
        val dark = mutableStateOf(false)
        rule.setContent { EaslieTheme(darkTheme = dark.value) {
            Box(Modifier.size(if (portrait.value) 300.dp else 600.dp, if (portrait.value) 500.dp else 248.dp)) {
                FloatingBoardUi(board, {}, {}, {}, {})
            }
        } }
        repeat(4) {
            rule.runOnIdle { portrait.value = it % 2 == 0; dark.value = it >= 2 }
            rule.onNodeWithContentDescription("Open board").assertIsDisplayed()
            rule.onNodeWithContentDescription("Return to full-screen mode").assertIsDisplayed()
            rule.onNodeWithContentDescription("Close floating board").assertIsDisplayed()
            rule.onNodeWithContentDescription("Add images").assertIsDisplayed()
        }
    }
}
