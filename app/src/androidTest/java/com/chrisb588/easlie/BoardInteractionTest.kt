package com.chrisb588.easlie

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasPoint
import com.chrisb588.easlie.canvas.CanvasTestTags
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BoardInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val board get() = (rule.activity.application as EaslieApplication).board
    private val node get() = rule.onNodeWithTag(CanvasTestTags.FullScreenBoard)
    private lateinit var fixture: Uri

    @Before
    fun importFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fixture = Uri.parse("content://com.chrisb588.easlie.test.images/interaction.png")
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        try {
            context.contentResolver.openOutputStream(fixture)!!.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
        rule.runOnIdle {
            board.items.toList().forEach { board.delete(it.id) }
            board.message = null
            board.viewport = com.chrisb588.easlie.canvas.CanvasViewport()
        }
        rule.waitUntil(5000) { board.items.isEmpty() && board.viewport == com.chrisb588.easlie.canvas.CanvasViewport() }
        rule.runOnIdle {
            board.enqueueImport(rule.activity.contentResolver, listOf(fixture))
        }
        rule.waitUntil(5000) { board.items.size == 1 && !board.importing }
    }

    private fun screen(point: CanvasPoint): Offset {
        val window = board.viewport.worldToWindow(point, board.windowSize)
        return Offset(window.x, window.y)
    }

    @Test
    fun draggingUnselectedImageDoesNotMoveItButSelectedImageMoves() {
        val initial = board.items.single()
        val start = screen(initial.center)
        node.performTouchInput { swipe(start, start + Offset(60f, 30f)) }
        rule.runOnIdle { assertEquals(initial, board.items.single()) }
        val selectedStart = screen(initial.center)
        node.performTouchInput { click(selectedStart) }
        node.performTouchInput { swipe(selectedStart, selectedStart + Offset(60f, 30f)) }
        rule.runOnIdle {
            val moved = board.items.single()
            assertEquals(initial.center.x + 60f, moved.center.x, 2f)
            assertEquals(initial.center.y + 30f, moved.center.y, 2f)
        }
    }

    @Test
    fun selectedCornerResizesProportionallyAndRotationHandleRotates() {
        val initial = board.items.single()
        node.performTouchInput { click(screen(initial.center)) }
        val corner = screen(initial.corner(1f, 1f))
        node.performTouchInput { swipe(corner, corner + Offset(60f, 30f)) }
        rule.runOnIdle {
            assertTrue(board.items.single().width > initial.width)
            assertEquals(initial.width / initial.height, board.items.single().width / board.items.single().height, 0.001f)
        }
        val resized = board.items.single()
        val gapPixels = 36f * rule.activity.resources.displayMetrics.density
        // Keep the rotation control inside the canvas below the app's controls.
        rule.runOnIdle {
            val zoom = minOf(board.viewport.zoom, (board.windowSize.height - gapPixels - 4f) / resized.height)
            assertTrue("Canvas must fit the rotation control", zoom > 0f)
            val centerY = resized.height * zoom / 2f + gapPixels + 2f
            board.viewport = board.viewport.copy(zoom = zoom, center = resized.center -
                CanvasPoint(0f, centerY - board.windowSize.height / 2f) / zoom)
        }
        val gap = gapPixels / board.viewport.zoom
        val rotation = screen(resized.localToWorld(CanvasPoint(0f, -resized.height / 2f - gap)))
        val end = screen(resized.center + CanvasPoint(resized.height / 2f + gap, 0f))
        node.performTouchInput {
            assertTrue(rotation.y in 0f..height.toFloat())
            assertTrue(end.y in 0f..height.toFloat())
            swipe(rotation, end)
        }
        rule.runOnIdle { assertEquals(90f, board.items.single().rotationDegrees, 3f) }
    }

    @Test
    fun doubleTapDeleteRemovesOnlyTheTappedImage() {
        rule.runOnIdle { board.enqueueImport(rule.activity.contentResolver, listOf(fixture)) }
        rule.waitUntil(5000) { board.items.size == 2 && !board.importing }
        val top = board.items.last()
        node.performTouchInput { doubleClick(screen(top.center)) }
        rule.onNodeWithText("Delete").performClick()
        rule.runOnIdle {
            assertEquals(1, board.items.size)
            assertFalse(board.items.any { it.id == top.id })
            assertFalse(board.images.containsKey(top.id))
        }
    }

    @Test
    fun rejectedBatchLeavesExistingBoardContentUnchanged() {
        val before = board.items.toList()
        rule.runOnIdle {
            board.enqueueImport(rule.activity.contentResolver,
                listOf(Uri.parse("content://com.chrisb588.easlie.test.images/missing.png")))
        }
        rule.waitUntil(5000) { !board.importing }
        rule.runOnIdle { assertEquals(before, board.items) }
        rule.onNodeWithText("1 image(s) could not be imported or saved.", substring = true).assertExists()
        rule.onNodeWithText("missing.png", substring = true).assertExists()
        rule.runOnIdle { assertTrue(board.canRetryImport) }
    }

    @Test
    fun tinySelectedImageStillMovesAndOpensDelete() {
        rule.runOnIdle {
            val item = board.items.single()
            board.update(item.copy(width = 20f, height = 10f))
        }
        var center = screen(board.items.single().center)
        node.performTouchInput { click(center) }
        val initial = board.items.single()
        node.performTouchInput { swipe(center, center + Offset(60f, 30f)) }
        rule.runOnIdle {
            assertEquals(initial.width, board.items.single().width, 0.001f)
            assertTrue(board.items.single().center != initial.center)
        }
        center = screen(board.items.single().center)
        node.performTouchInput { doubleClick(center) }
        rule.onNodeWithText("Delete").performClick()
        rule.runOnIdle { assertTrue(board.items.isEmpty()) }
    }

    @Test
    fun selectedLowerImageHandleDoesNotInterceptTopImageTap() {
        val lower = board.items.single()
        node.performTouchInput { click(screen(lower.center)) }
        rule.runOnIdle { board.enqueueImport(rule.activity.contentResolver, listOf(fixture)) }
        rule.waitUntil(5000) { board.items.size == 2 && !board.importing }
        rule.runOnIdle {
            val top = board.items.last()
            board.update(top.copy(center = lower.corner(1f, 1f), width = 40f, height = 20f))
        }
        val top = board.items.last()
        val center = screen(top.center)
        node.performTouchInput { click(center) }
        node.performTouchInput { swipe(center, center + Offset(60f, 30f)) }
        rule.runOnIdle {
            assertEquals(lower, board.items.first())
            assertTrue(board.items.last().center != top.center)
            assertEquals(top.width, board.items.last().width, 0.001f)
        }
    }

    @Test
    fun nearestTinyCornerKeepsItsOppositeCornerFixed() {
        rule.runOnIdle {
            val item = board.items.single()
            board.update(item.copy(width = 20f, height = 10f))
        }
        val initial = board.items.single()
        node.performTouchInput { click(screen(initial.center)) }
        val corner = screen(initial.corner(1f, 1f))
        node.performTouchInput { swipe(corner, corner + Offset(40f, 20f)) }
        rule.runOnIdle {
            val anchor = initial.corner(-1f, -1f)
            val resizedAnchor = board.items.single().corner(-1f, -1f)
            assertEquals(anchor.x, resizedAnchor.x, 0.001f)
            assertEquals(anchor.y, resizedAnchor.y, 0.001f)
        }
    }
}
