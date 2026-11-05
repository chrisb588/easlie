package com.chrisb588.easlie

import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasPoint
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.canvas.FullScreenCanvas
import com.chrisb588.easlie.canvas.CanvasTestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CanvasViewportOwnershipTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fullScreenPinchKeepsOffCenterFocalPointAndViewportOwnership() = verifyViewport(false)
    @Test fun floatingPinchKeepsOffCenterFocalPointAndViewportOwnership() = verifyViewport(true)

    private fun verifyViewport(floating: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = Uri.parse("content://com.chrisb588.easlie.test.images/clipping.png")
        instrumentation.targetContext.contentResolver.call(fixture, "create-clipping-fixture", null, null)
        val board = BoardStore()
        rule.setContent { FullScreenCanvas(board, Modifier.size(300.dp), floatingMode = floating) }
        rule.runOnIdle {
            board.enqueueImport(instrumentation.targetContext.contentResolver, listOf(fixture, fixture))
        }
        rule.waitUntil(5000) { board.items.size == 2 && !board.importing }
        val node = rule.onNodeWithTag(if (floating) CanvasTestTags.FloatingBoard else CanvasTestTags.FullScreenBoard)
        val measuredSize = node.fetchSemanticsNode().size
        val size = CanvasSize(measuredSize.width.toFloat(), measuredSize.height.toFloat())
        val initial = board.viewportFor(floating)
        val focal = Offset(size.width * 0.4f, size.height * 0.45f)
        val focalPoint = CanvasPoint(focal.x, focal.y)
        val first = focal - Offset(40f, 0f)
        val second = focal + Offset(40f, 0f)

        for ((firstOnImage, secondOnImage) in listOf(true to true, true to false, false to true)) {
            rule.runOnIdle {
                board.setViewport(initial, floating)
                board.items.toList().forEachIndexed { index, item ->
                    val point = when {
                        index == 0 && firstOnImage -> first
                        index == 1 && secondOnImage -> second
                        else -> Offset(size.width * 0.8f, size.height * 0.8f)
                    }
                    board.update(item.copy(
                        center = initial.windowToWorld(CanvasPoint(point.x, point.y), size),
                        width = 40f / initial.zoom,
                        height = 40f / initial.zoom,
                    ))
                }
            }
            val items = board.items.toList()
            rule.runOnIdle {
                assertEquals(firstOnImage, items.any {
                    it.contains(initial.windowToWorld(CanvasPoint(first.x, first.y), size))
                })
                assertEquals(secondOnImage, items.any {
                    it.contains(initial.windowToWorld(CanvasPoint(second.x, second.y), size))
                })
            }
            node.performTouchInput {
                down(0, first)
                down(1, second)
                updatePointerTo(0, focal - Offset(60f, 0f))
                updatePointerTo(1, focal + Offset(60f, 0f))
                move()
            }
            val pinched = board.viewportFor(floating)
            rule.runOnIdle {
                assertEquals(initial.zoom * 1.5f, pinched.zoom, 0.001f)
                val before = initial.windowToWorld(focalPoint, size)
                val after = pinched.windowToWorld(focalPoint, size)
                assertEquals(before.x, after.x, 0.001f)
                assertEquals(before.y, after.y, 0.001f)
                assertEquals(items, board.items)
            }
            // Rotating the finger pair at fixed distance must not rotate or scale the board.
            node.performTouchInput {
                updatePointerTo(0, focal - Offset(0f, 60f))
                updatePointerTo(1, focal + Offset(0f, 60f))
                move()
            }
            rule.runOnIdle {
                val twisted = board.viewportFor(floating)
                assertEquals(pinched.zoom, twisted.zoom, 0.001f)
                assertEquals(pinched.center.x, twisted.center.x, 0.001f)
                assertEquals(pinched.center.y, twisted.center.y, 0.001f)
                assertEquals(items, board.items)
            }
            val beforeLift = board.viewportFor(floating)
            node.performTouchInput {
                up(1)
                moveTo(0, focal - Offset(0f, 100f))
            }
            rule.runOnIdle {
                assertEquals("The remaining finger stays inactive until all fingers lift", beforeLift, board.viewportFor(floating))
                assertEquals(items, board.items)
            }
            node.performTouchInput { up(0) }

            // A fresh continuous drag still pans, proving the pinch did not select an image.
            val image = if (firstOnImage) items.first() else items.last()
            val window = beforeLift.worldToWindow(image.center, size)
            val dragStart = Offset(window.x, window.y)
            node.performTouchInput { down(dragStart) }
            for (distance in listOf(30f, 60f, 90f)) {
                node.performTouchInput { moveTo(dragStart + Offset(distance, 0f)) }
                rule.runOnIdle {
                    val expected = beforeLift.pannedBy(CanvasPoint(distance, 0f))
                    assertEquals(expected.center.x, board.viewportFor(floating).center.x, 0.001f)
                    assertEquals(expected.center.y, board.viewportFor(floating).center.y, 0.001f)
                    assertEquals(expected.zoom, board.viewportFor(floating).zoom, 0.001f)
                    assertEquals(items, board.items)
                }
            }
            node.performTouchInput { up() }
            rule.runOnIdle { assertEquals(items, board.items) }
        }
    }
}
