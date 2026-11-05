package com.chrisb588.easlie

import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasPoint
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.canvas.CanvasTestTags
import com.chrisb588.easlie.canvas.FullScreenCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class CanvasResizeContinuationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fullScreenRemainingFingerResumesImageDragging() = verifyContinuation(false)
    @Test fun floatingRemainingFingerResumesImageDragging() = verifyContinuation(true)
    @Test fun fullScreenDoubleTapSurvivesSelectionRecomposition() = verifyDoubleTap(false)
    @Test fun floatingDoubleTapSurvivesSelectionRecomposition() = verifyDoubleTap(true)

    private fun importFixture(board: BoardStore) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = Uri.parse("content://com.chrisb588.easlie.test.images/clipping.png")
        context.contentResolver.call(fixture, "create-clipping-fixture", null, null)
        rule.runOnIdle { board.enqueueImport(context.contentResolver, listOf(fixture)) }
        rule.waitUntil(5000) { board.items.size == 1 && !board.importing }
    }

    private fun verifyContinuation(floating: Boolean) {
        val board = BoardStore()
        try {
            rule.setContent { FullScreenCanvas(board, Modifier.size(500.dp), floatingMode = floating) }
            importFixture(board)
            val viewport = board.viewportFor(floating)
            val node = rule.onNodeWithTag(if (floating) CanvasTestTags.FloatingBoard else CanvasTestTags.FullScreenBoard)
            for (lifted in listOf(1, 0)) {
                rule.runOnIdle {
                    board.update(board.items.single().copy(center = viewport.center, width = 200f, height = 150f))
                }
                node.performTouchInput { down(center); up() }
                node.performTouchInput { down(0, center); moveBy(0, Offset(35f, 0f)) }
                val transition = board.items.single()
                node.performTouchInput {
                    val point = viewport.worldToWindow(transition.corner(1f, 1f), CanvasSize(width.toFloat(), height.toFloat()))
                    down(1, Offset(point.x, point.y)); moveBy(1, Offset(40f, 40f))
                }
                val resized = board.items.single()
                rule.runOnIdle { assertNotEquals(transition.width, resized.width) }
                node.performTouchInput { up(lifted) }
                rule.runOnIdle { assertEquals("Lifting a finger must not jump", resized, board.items.single()) }
                val remaining = 1 - lifted
                node.performTouchInput { moveBy(remaining, Offset(50f, 15f)) }
                val moved = board.items.single()
                rule.runOnIdle {
                    assertEquals(resized.copy(center = resized.center + CanvasPoint(50f, 15f) / viewport.zoom), moved)
                    assertEquals(viewport, board.viewportFor(floating))
                }
                // The same continuous gesture can transition back to resizing at its current corner.
                node.performTouchInput {
                    val point = viewport.worldToWindow(moved.corner(-1f, -1f), CanvasSize(width.toFloat(), height.toFloat()))
                    down(lifted, Offset(point.x, point.y)); moveBy(lifted, Offset(-30f, -30f))
                }
                rule.runOnIdle {
                    assertNotEquals(moved.width, board.items.single().width)
                    assertEquals(moved.center, board.items.single().center)
                    assertEquals(viewport, board.viewportFor(floating))
                }
                node.performTouchInput { up(lifted); up(remaining) }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { board.releaseImages() }
        }
    }

    private fun verifyDoubleTap(floating: Boolean) {
        val board = BoardStore()
        try {
            rule.setContent { FullScreenCanvas(board, Modifier.size(300.dp), floatingMode = floating) }
            importFixture(board)
            val node = rule.onNodeWithTag(if (floating) CanvasTestTags.FloatingBoard else CanvasTestTags.FullScreenBoard)
            node.performTouchInput { down(center); up() }
            rule.waitForIdle()
            rule.onNodeWithTag(CanvasTestTags.Deselect).assertExists()
            node.performTouchInput { advanceEventTime(100); down(center); up() }
            rule.onNodeWithText("Delete").assertExists()
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { board.releaseImages() }
        }
    }
}
