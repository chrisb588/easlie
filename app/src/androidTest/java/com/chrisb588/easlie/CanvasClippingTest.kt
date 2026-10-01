package com.chrisb588.easlie

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasViewport
import com.chrisb588.easlie.canvas.FullScreenCanvas
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CanvasClippingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun zoomedAndResizedRotatedImagesCannotPaintOverControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = Uri.parse("content://com.chrisb588.easlie.test.images/clipping.png")
        instrumentation.targetContext.contentResolver.call(fixture, "create-clipping-fixture", null, null)
        val board = BoardStore()
        try {
            rule.setContent {
                Column(Modifier.size(300.dp).background(Color.Blue).testTag("clipping-root")) {
                    Box(Modifier.fillMaxWidth().height(80.dp))
                    FullScreenCanvas(board, Modifier.weight(1f))
                }
            }
            rule.runOnIdle {
                board.enqueueImport(instrumentation.targetContext.contentResolver,
                    listOf(fixture))
            }
            rule.waitUntil(5000) { board.items.size == 1 && board.images.isNotEmpty() }
            for (resizeItem in listOf(false, true)) {
                rule.runOnIdle {
                    val width = board.windowSize.width * if (resizeItem) 8f else 1f
                    board.update(board.items.single().copy(width = width, height = width / 2f,
                        rotationDegrees = 15f))
                    board.viewport = CanvasViewport(zoom = if (resizeItem) 1f else 8f)
                }
                val pixels = rule.onNodeWithTag("clipping-root").captureToImage().toPixelMap()
                assertEquals("Image escaped into the controls", Color.Blue,
                    pixels[pixels.width / 2, pixels.height / 8])
                assertEquals("Fixture must still render inside the board", Color.Red,
                    pixels[pixels.width / 2, pixels.height * 3 / 4])
            }
        } finally {
            rule.runOnIdle { board.items.toList().forEach { board.delete(it.id) }; board.releaseImages() }
        }
    }
}
