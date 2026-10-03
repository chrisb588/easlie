package com.chrisb588.easlie

import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasTestTags
import com.chrisb588.easlie.canvas.FullScreenCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class CanvasGestureOwnershipTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fullScreenInputOwnsUnselectedDragsAndPinches() = verifyHost(false)
    @Test fun floatingInputOwnsUnselectedDragsAndPinches() = verifyHost(true)

    private fun verifyHost(floating: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = Uri.parse("content://com.chrisb588.easlie.test.images/clipping.png")
        instrumentation.targetContext.contentResolver.call(fixture, "create-clipping-fixture", null, null)
        val board = BoardStore()
        rule.setContent { FullScreenCanvas(board, Modifier.size(300.dp), floatingMode = floating) }
        rule.runOnIdle {
            board.enqueueImport(instrumentation.targetContext.contentResolver, listOf(fixture, fixture))
        }
        rule.waitUntil(5000) { board.items.size == 2 }
        rule.runOnIdle {
            val center = board.viewportFor(floating).center
            board.items.toList().forEach { board.update(it.copy(center = center, width = 200f, height = 200f)) }
        }
        val node = rule.onNodeWithTag(if (floating) CanvasTestTags.FloatingBoard else CanvasTestTags.FullScreenBoard)
        val items = board.items.toList()
        val initial = board.viewportFor(floating)
        node.performTouchInput {
            down(center)
            moveTo(center + Offset(70f, 0f), delayMillis = 100)
            up()
        }
        rule.runOnIdle {
            assertNotEquals(initial, board.viewportFor(floating))
            assertEquals(items, board.items)
            board.setViewport(initial, floating)
        }
        node.performTouchInput {
            down(0, center - Offset(30f, 0f))
            down(1, center + Offset(30f, 0f))
            moveTo(0, center - Offset(60f, 0f))
            moveTo(1, center + Offset(60f, 0f))
            up(1)
            up(0)
        }
        rule.runOnIdle {
            assertNotEquals(initial.zoom, board.viewportFor(floating).zoom)
            assertEquals(initial.center.x, board.viewportFor(floating).center.x, 0.01f)
            assertEquals(initial.center.y, board.viewportFor(floating).center.y, 0.01f)
            assertEquals(items, board.items)
            board.setViewport(initial, floating)
        }
        // The pinch did not select an image: the following drag still pans.
        node.performTouchInput { down(center); moveTo(center + Offset(70f, 0f)); up() }
        rule.runOnIdle {
            assertEquals(items, board.items)
            board.setViewport(initial, floating)
        }
        // Stationary tap selects the topmost item; the next drag moves only it.
        node.performTouchInput { down(center); moveTo(center + Offset(1f, 0f)); up() }
        node.performTouchInput { down(center); moveTo(center + Offset(70f, 0f)); up() }
        rule.runOnIdle {
            assertEquals(initial, board.viewportFor(floating))
            assertEquals(items.first(), board.items.first())
            assertNotEquals(items.last().center, board.items.last().center)
        }
        // Adding another finger after a selected-image move cannot claim the viewport.
        node.performTouchInput {
            val target = center + Offset(70f, 0f)
            down(0, target)
            moveTo(0, target + Offset(35f, 0f))
            down(1, center - Offset(80f, 0f))
            moveTo(1, center - Offset(120f, 0f))
            up(1)
            up(0)
        }
        rule.runOnIdle { assertEquals(initial, board.viewportFor(floating)) }
    }
}
