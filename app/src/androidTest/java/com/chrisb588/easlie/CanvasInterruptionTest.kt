package com.chrisb588.easlie

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
import java.io.File
import java.util.UUID

class CanvasInterruptionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fullScreenRemovalDuringMoveSavesDisplayedEdit() = verifyRemoval(false, Edit.Move)
    @Test fun floatingRemovalDuringMoveSavesDisplayedEdit() = verifyRemoval(true, Edit.Move)
    @Test fun fullScreenRemovalDuringRotationSavesDisplayedEdit() = verifyRemoval(false, Edit.Rotate)
    @Test fun floatingRemovalDuringRotationSavesDisplayedEdit() = verifyRemoval(true, Edit.Rotate)
    @Test fun fullScreenRemovalDuringResizeSavesDisplayedEdit() = verifyRemoval(false, Edit.Resize)
    @Test fun floatingRemovalDuringResizeSavesDisplayedEdit() = verifyRemoval(true, Edit.Resize)

    private enum class Edit { Move, Rotate, Resize }

    private fun verifyRemoval(floating: Boolean, edit: Edit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = Uri.parse("content://com.chrisb588.easlie.test.images/clipping.png")
        context.contentResolver.call(fixture, "create-clipping-fixture", null, null)
        val directory = File(context.cacheDir, "interruption-${UUID.randomUUID()}")
        val board = BoardStore(directory)
        val mounted = mutableStateOf(true)
        try {
            rule.setContent {
                Box(Modifier.size(500.dp)) {
                    if (mounted.value) FullScreenCanvas(board, floatingMode = floating)
                }
            }
            rule.runOnIdle { board.enqueueImport(context.contentResolver, listOf(fixture)) }
            rule.waitUntil(5000) { board.items.size == 1 && !board.importing }
            val viewport = board.viewportFor(floating)
            rule.runOnIdle {
                board.update(board.items.single().copy(
                    center = viewport.center, width = 200f, height = 120f, rotationDegrees = 23f,
                ))
            }
            val tag = if (floating) CanvasTestTags.FloatingBoard else CanvasTestTags.FullScreenBoard
            val node = rule.onNodeWithTag(tag)
            node.performTouchInput { down(center); up() }
            val before = board.items.single()
            when (edit) {
                Edit.Move -> node.performTouchInput { down(center); moveBy(Offset(60f, 25f)) }
                Edit.Rotate -> node.performTouchInput {
                    val gap = 36f * context.resources.displayMetrics.density / viewport.zoom
                    val world = before.localToWorld(CanvasPoint(0f, -before.height / 2f - gap))
                    val point = viewport.worldToWindow(world, CanvasSize(width.toFloat(), height.toFloat()))
                    down(Offset(point.x, point.y)); moveBy(Offset(60f, 0f))
                }
                Edit.Resize -> {
                    node.performTouchInput { down(0, center); moveBy(0, Offset(35f, 0f)) }
                    val transition = board.items.single()
                    node.performTouchInput {
                        val point = viewport.worldToWindow(transition.corner(1f, 1f),
                            CanvasSize(width.toFloat(), height.toFloat()))
                        down(1, Offset(point.x, point.y)); moveBy(1, Offset(50f, 40f))
                    }
                }
            }
            val displayed = board.items.toList()
            rule.runOnIdle {
                when (edit) {
                    Edit.Move -> assertNotEquals(before.center, displayed.single().center)
                    Edit.Rotate -> assertNotEquals(before.rotationDegrees, displayed.single().rotationDegrees)
                    Edit.Resize -> assertNotEquals(before.width, displayed.single().width)
                }
                assertEquals(viewport, board.viewportFor(floating))
                // Remove the canvas while pointers are still down, without sending up or cancel.
                mounted.value = false
            }
            rule.waitForIdle()
            node.assertDoesNotExist()
            rule.waitUntil(5000) {
                runCatching { BoardStorage(directory).load().snapshot.items == displayed }.getOrDefault(false)
            }
            rule.runOnIdle { assertEquals(displayed, board.items) }
            // Clear the test injector's old pointers only after disposal and persistence were checked.
            rule.onRoot().performTouchInput { cancel() }
            rule.runOnIdle { mounted.value = true }
            rule.waitForIdle()
            node.performTouchInput {
                val point = viewport.worldToWindow(displayed.single().center,
                    CanvasSize(width.toFloat(), height.toFloat()))
                down(Offset(point.x, point.y)); up()
            }
            node.performTouchInput {
                val point = viewport.worldToWindow(displayed.single().center,
                    CanvasSize(width.toFloat(), height.toFloat()))
                down(Offset(point.x, point.y)); moveBy(Offset(50f, 0f)); up()
            }
            val continued = board.items.toList()
            rule.runOnIdle {
                val expected = displayed.single().copy(center = displayed.single().center + CanvasPoint(50f / viewport.zoom, 0f))
                assertEquals(expected, continued.single())
                assertEquals(viewport, board.viewportFor(floating))
            }
            rule.waitUntil(5000) {
                runCatching { BoardStorage(directory).load().snapshot.items == continued }.getOrDefault(false)
            }
        } finally {
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
            instrumentation.runOnMainSync { board.releaseImages() }
            directory.deleteRecursively()
        }
    }
}
