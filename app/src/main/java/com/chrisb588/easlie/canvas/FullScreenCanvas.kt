package com.chrisb588.easlie.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

object CanvasTestTags {
    const val FullScreenBoard = "full-screen-board"
}

private val TestMarkerPosition = CanvasPoint(0f, 0f)
private const val TestMarkerRadius = 48f
private const val TestMarkerOutlineWidth = 6f

@Composable
fun FullScreenCanvas(modifier: Modifier = Modifier) {
    val viewportState = remember { mutableStateOf(CanvasViewport()) }
    val windowSizeState = remember { mutableStateOf(CanvasSize(0f, 0f)) }
    val backgroundColor = MaterialTheme.colorScheme.background
    val markerColor = MaterialTheme.colorScheme.primary
    val markerOutlineColor = MaterialTheme.colorScheme.onPrimary

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .onSizeChanged { size ->
                windowSizeState.value = CanvasSize(size.width.toFloat(), size.height.toFloat())
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val windowSize = windowSizeState.value
                    if (windowSize.width > 0f && windowSize.height > 0f) {
                        viewportState.value = viewportState.value.transformedBy(
                            focalPoint = centroid.toCanvasPoint(),
                            pan = pan.toCanvasPoint(),
                            zoomChange = zoom,
                            windowSize = windowSize,
                        )
                    }
                }
            }
            .testTag(CanvasTestTags.FullScreenBoard)
            .semantics {
                contentDescription = "Full-screen board with fixed test marker"
            },
    ) {
        val windowSize = CanvasSize(size.width, size.height)
        val markerCenter = viewportState.value.worldToWindow(TestMarkerPosition, windowSize)

        drawCircle(
            color = markerColor,
            radius = TestMarkerRadius * viewportState.value.zoom,
            center = markerCenter.toOffset(),
        )
        drawCircle(
            color = markerOutlineColor,
            radius = TestMarkerRadius * viewportState.value.zoom,
            center = markerCenter.toOffset(),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = TestMarkerOutlineWidth * viewportState.value.zoom,
            ),
        )
    }
}

private fun Offset.toCanvasPoint() = CanvasPoint(x, y)

private fun CanvasPoint.toOffset() = Offset(x, y)
