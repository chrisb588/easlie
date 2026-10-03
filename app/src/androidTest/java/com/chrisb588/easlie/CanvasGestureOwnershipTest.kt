package com.chrisb588.easlie

import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasPoint
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.canvas.CanvasTestTags
import com.chrisb588.easlie.canvas.FullScreenCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class CanvasGestureOwnershipTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fullScreenInputOwnsUnselectedDragsAndPinches() = verifyHost(false)
    @Test fun floatingInputOwnsUnselectedDragsAndPinches() = verifyHost(true)

    @Test fun fullScreenResizeHandleOverAnotherImageKeepsOwnership() = verifyOverlappingHandle(false, false)
    @Test fun floatingResizeHandleOverAnotherImageKeepsOwnership() = verifyOverlappingHandle(true, false)
    @Test fun fullScreenRotationHandleOverAnotherImageKeepsOwnership() = verifyOverlappingHandle(false, true)
    @Test fun floatingRotationHandleOverAnotherImageKeepsOwnership() = verifyOverlappingHandle(true, true)

    private fun verifyOverlappingHandle(floating: Boolean, rotating: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = Uri.parse("content://com.chrisb588.easlie.test.images/clipping.png")
        instrumentation.targetContext.contentResolver.call(fixture, "create-clipping-fixture", null, null)
        val board = BoardStore()
        rule.setContent { FullScreenCanvas(board, Modifier.size(300.dp), floatingMode = floating) }
        rule.runOnIdle {
            board.enqueueImport(instrumentation.targetContext.contentResolver, listOf(fixture, fixture))
        }
        rule.waitUntil(5000) { board.items.size == 2 && !board.importing }
        val initialViewport = board.viewportFor(floating)
        // Cover controls both with a higher image and with an image below selection.
        val selectedId = if (rotating) board.items.last().id else board.items.first().id
        val otherId = board.items.first { it.id != selectedId }.id
        rule.runOnIdle {
            board.items.toList().forEach { item ->
                board.update(item.copy(
                    center = initialViewport.center + if (item.id == selectedId) CanvasPoint(0f, 0f) else CanvasPoint(200f, 0f),
                    width = 160f, height = 160f,
                ))
            }
        }
        val node = rule.onNodeWithTag(if (floating) CanvasTestTags.FloatingBoard else CanvasTestTags.FullScreenBoard)
        node.performTouchInput { down(center); up() }
        val selected = board.items.first { it.id == selectedId }
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val handleWorld = if (rotating) {
            selected.localToWorld(CanvasPoint(0f, -selected.height / 2f - 36f * density / initialViewport.zoom))
        } else selected.corner(1f, 1f)
        rule.runOnIdle {
            val other = board.items.first { it.id == otherId }
            board.update(other.copy(center = handleWorld, width = 100f, height = 100f))
        }
        val other = board.items.first { it.id == otherId }
        node.performTouchInput {
            val point = initialViewport.worldToWindow(handleWorld, CanvasSize(width.toFloat(), height.toFloat()))
            val handle = Offset(point.x, point.y)
            down(handle)
            moveTo(handle + if (rotating) Offset(60f, 0f) else Offset(45f, 45f))
            up()
        }
        rule.runOnIdle {
            assertEquals("Handle drag must not pan", initialViewport, board.viewportFor(floating))
            assertEquals(other, board.items.first { it.id == otherId })
            val changed = board.items.first { it.id == selectedId }
            if (rotating) {
                assertNotEquals(selected.rotationDegrees, changed.rotationDegrees)
                assertEquals(selected.center, changed.center)
                assertEquals(selected.width, changed.width, 0.001f)
            } else {
                assertNotEquals(selected.width, changed.width)
                assertEquals(selected.width / selected.height, changed.width / changed.height, 0.001f)
                assertEquals(selected.corner(-1f, -1f), changed.corner(-1f, -1f))
            }
            board.update(selected)
        }
        // A tap on the same control still selects the topmost image underneath it.
        node.performTouchInput {
            val point = initialViewport.worldToWindow(handleWorld, CanvasSize(width.toFloat(), height.toFloat()))
            val handle = Offset(point.x, point.y)
            down(handle); up()
        }
        node.performTouchInput {
            val point = initialViewport.worldToWindow(handleWorld, CanvasSize(width.toFloat(), height.toFloat()))
            val start = Offset(point.x, point.y)
            down(start); moveTo(start + Offset(60f, 20f)); up()
        }
        rule.runOnIdle {
            assertEquals(initialViewport, board.viewportFor(floating))
            assertEquals(selected, board.items.first { it.id == selectedId })
            assertNotEquals(other.center, board.items.first { it.id == otherId }.center)
            assertEquals(other.width, board.items.first { it.id == otherId }.width, 0.001f)
        }
    }

    private fun verifyHost(floating: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = Uri.parse("content://com.chrisb588.easlie.test.images/clipping.png")
        instrumentation.targetContext.contentResolver.call(fixture, "create-clipping-fixture", null, null)
        val directory = File(instrumentation.targetContext.cacheDir, "gesture-${UUID.randomUUID()}")
        val board = BoardStore(directory)
        try {
            rule.setContent { FullScreenCanvas(board, Modifier.size(500.dp), floatingMode = floating) }
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
            }
            val pinchViewport = board.viewportFor(floating)
            node.performTouchInput { moveBy(0, Offset(70f, 0f)); up(0) }
            rule.runOnIdle {
                assertEquals(pinchViewport, board.viewportFor(floating))
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
            rule.runOnIdle {
                board.update(board.items.last().copy(center = initial.center, width = 200f, height = 200f))
            }
            // Transition at a current corner, then move both fingers and their midpoint.
            val transitionStart = board.items.last()
            node.performTouchInput {
                val point = initial.worldToWindow(transitionStart.center, CanvasSize(width.toFloat(), height.toFloat()))
                val first = Offset(point.x, point.y)
                down(0, first)
                moveTo(0, first + Offset(35f, 0f))
            }
            val transition = board.items.last()
            node.performTouchInput {
                val point = initial.worldToWindow(transition.corner(1f, 1f), CanvasSize(width.toFloat(), height.toFloat()))
                val corner = Offset(point.x, point.y)
                down(1, corner)
                moveBy(0, Offset(-20f, -20f))
            }
            val firstFingerResize = board.items.last()
            rule.runOnIdle { assertNotEquals(transition.width, firstFingerResize.width) }
            node.performTouchInput { moveBy(1, Offset(40f, 40f)) }
            rule.runOnIdle {
                assertNotEquals(firstFingerResize.width, board.items.last().width)
                val resized = board.items.last()
                assertNotEquals(transition.width, resized.width)
                assertEquals(transition.center, resized.center)
                assertEquals(transition.rotationDegrees, resized.rotationDegrees)
                assertEquals(transition.width / transition.height, resized.width / resized.height, 0.001f)
                assertEquals(initial, board.viewportFor(floating))
            }
            val beforeMidpoint = board.items.last()
            node.performTouchInput {
                moveBy(0, Offset(15f, 10f), delayMillis = 0)
                moveBy(1, Offset(15f, 10f), delayMillis = 0)
                up(0)
            }
            val afterLift = board.items.last()
            node.performTouchInput { moveBy(1, Offset(80f, 0f)); up(1) }
            rule.runOnIdle {
                assertEquals(beforeMidpoint.center, afterLift.center)
                assertEquals(beforeMidpoint.width, afterLift.width, 0.01f)
                assertEquals(beforeMidpoint.height, afterLift.height, 0.01f)
                assertEquals(afterLift, board.items.last())
                assertEquals(initial, board.viewportFor(floating))
            }
            rule.onNodeWithTag(CanvasTestTags.Deselect).performClick()
            val deselected = board.items.toList()
            node.performTouchInput { down(center); moveTo(center + Offset(60f, 0f)); up() }
            rule.runOnIdle { assertEquals(deselected, board.items) }
            rule.runOnIdle {
                board.update(board.items.last().copy(center = initial.center, width = 200f, height = 200f))
            }
            // Select again to exercise the unchanged opposite-corner and rotation anchors.
            val select = board.items.last()
            node.performTouchInput {
                val point = board.viewportFor(floating).worldToWindow(select.center, CanvasSize(width.toFloat(), height.toFloat()))
                down(Offset(point.x, point.y)); up()
            }
            rule.runOnIdle { board.setViewport(initial, floating) }
            val beforeResize = board.items.last()
            node.performTouchInput {
                val point = initial.worldToWindow(beforeResize.corner(1f, 1f), CanvasSize(width.toFloat(), height.toFloat()))
                val corner = Offset(point.x, point.y)
                down(corner); moveTo(corner + Offset(45f, 45f)); up()
            }
            rule.runOnIdle {
                assertEquals(initial, board.viewportFor(floating))
                assertNotEquals(beforeResize.width, board.items.last().width)
                assertEquals(beforeResize.corner(-1f, -1f), board.items.last().corner(-1f, -1f))
            }
            val beforeRotate = board.items.last()
            val density = instrumentation.targetContext.resources.displayMetrics.density
            node.performTouchInput {
                val world = beforeRotate.localToWorld(CanvasPoint(0f, -beforeRotate.height / 2f - 36f * density))
                val point = initial.worldToWindow(world, CanvasSize(width.toFloat(), height.toFloat()))
                val handle = Offset(point.x, point.y)
                down(handle); moveTo(handle + Offset(60f, 0f)); cancel()
            }
            rule.runOnIdle {
                assertEquals(initial, board.viewportFor(floating))
                assertNotEquals(beforeRotate.rotationDegrees, board.items.last().rotationDegrees)
            }
            val interrupted = board.items.toList()
            rule.waitUntil(5000) {
                runCatching { BoardStorage(directory).load().snapshot.items == interrupted }.getOrDefault(false)
            }
            // Cancellation released ownership: a fresh selected-image drag moves normally.
            node.performTouchInput {
                val point = initial.worldToWindow(interrupted.last().center, CanvasSize(width.toFloat(), height.toFloat()))
                down(Offset(point.x, point.y)); moveBy(Offset(50f, 0f)); up()
            }
            rule.runOnIdle { assertNotEquals(interrupted.last().center, board.items.last().center) }
            val beforeCanceledMove = board.items.last()
            node.performTouchInput {
                val point = initial.worldToWindow(beforeCanceledMove.center, CanvasSize(width.toFloat(), height.toFloat()))
                down(Offset(point.x, point.y)); moveBy(Offset(45f, 0f)); cancel()
            }
            val canceledMove = board.items.toList()
            rule.runOnIdle { assertNotEquals(beforeCanceledMove.center, canceledMove.last().center) }
            rule.waitUntil(5000) {
                runCatching { BoardStorage(directory).load().snapshot.items == canceledMove }.getOrDefault(false)
            }
            rule.runOnIdle {
                board.update(board.items.last().copy(center = initial.center, width = 200f, height = 200f))
            }
            val resizeStart = board.items.last()
            // Cancel after the two-finger transition, retaining its displayed center and size.
            node.performTouchInput {
                val point = initial.worldToWindow(resizeStart.center, CanvasSize(width.toFloat(), height.toFloat()))
                down(0, Offset(point.x, point.y)); moveBy(0, Offset(35f, 0f))
            }
            val beforeCanceledResize = board.items.last()
            node.performTouchInput {
                val point = initial.worldToWindow(beforeCanceledResize.corner(1f, 1f), CanvasSize(width.toFloat(), height.toFloat()))
                down(1, Offset(point.x, point.y)); moveBy(1, Offset(40f, 40f)); cancel()
            }
            val canceledResize = board.items.toList()
            rule.runOnIdle {
                assertNotEquals(beforeCanceledResize.width, canceledResize.last().width)
                assertEquals(beforeCanceledResize.center, canceledResize.last().center)
            }
            rule.waitUntil(5000) {
                runCatching { BoardStorage(directory).load().snapshot.items == canceledResize }.getOrDefault(false)
            }
        } finally {
            instrumentation.runOnMainSync { board.releaseImages() }
            directory.deleteRecursively()
        }
    }
}
