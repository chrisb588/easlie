package com.chrisb588.easlie.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import com.chrisb588.easlie.BoardStore
import kotlin.math.roundToInt
import com.chrisb588.easlie.images.intersects

object CanvasTestTags {
    const val FullScreenBoard = "full-screen-board"
    const val FloatingBoard = "floating-board"
}

private enum class DragKind { Pan, Move, Resize, Rotate }
private data class Handle(val xSign: Float, val ySign: Float)
private val corners = listOf(Handle(-1f, -1f), Handle(1f, -1f), Handle(1f, 1f), Handle(-1f, 1f))

@Composable
fun FullScreenCanvas(board: BoardStore, modifier: Modifier = Modifier, floatingMode: Boolean = false) {
    var selectedId by remember(board.activeBoardId) { mutableStateOf<String?>(null) }
    var menuId by remember(board.activeBoardId) { mutableStateOf<String?>(null) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current.density
    val imageOwner = remember(board) { Any() }
    val interactive = board.isActiveCanvas(imageOwner)
    var canvasSize by remember { mutableStateOf(CanvasSize(0f, 0f)) }
    DisposableEffect(board, imageOwner) {
        board.attachCanvas(imageOwner)
        onDispose { board.save(); board.detachCanvas(imageOwner) }
    }
    LaunchedEffect(board, density, floatingMode, imageOwner) {
        snapshotFlow {
            if (board.isActiveCanvas(imageOwner)) Triple(board.items, board.viewportFor(floatingMode), canvasSize)
            else null
        }.collect { state ->
            if (state != null && state.third.width > 0f && state.third.height > 0f) {
                board.refreshImages(imageOwner, density, floatingMode, state.third)
            }
        }
    }
    val handleRadius = with(LocalDensity.current) { 12.dp.toPx() }
    val rotationGap = with(LocalDensity.current) { 36.dp.toPx() }
    val backgroundColor = MaterialTheme.colorScheme.background
    val selectionColor = MaterialTheme.colorScheme.primary

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().clipToBounds().background(backgroundColor)
                .onSizeChanged { canvasSize = CanvasSize(it.width.toFloat(), it.height.toFloat()) }
                .testTag(if (floatingMode) CanvasTestTags.FloatingBoard else CanvasTestTags.FullScreenBoard)
                .semantics { contentDescription = "Reference image board. Tap to select; drag a selected image or its handles. Double-tap for Delete." }
                .pointerInput(board.activeBoardId, interactive, board, handleRadius, rotationGap) {
                    if (!interactive) return@pointerInput
                    var lastTapTime = 0L
                    var lastTapId: String? = null
                    var lastTapPosition = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        try {
                            menuId = null
                            val start = down.position.toCanvasPoint()
                            val viewportAtStart = board.viewportFor(floatingMode)
                            val size = canvasSize
                            val worldStart = viewportAtStart.windowToWorld(start, size)
                            val selected = board.items.firstOrNull { it.id == selectedId }
                            val hit = board.items.hitTest(worldStart)
                            val handlesBelongToHit = hit == null || hit.id == selectedId
                            val centerDistance = selected?.let {
                                (viewportAtStart.worldToWindow(it.center, size).toOffset() - down.position).getDistance()
                            } ?: 0f
                            // Preserve a move target near the center even when a zoomed-out image is tiny.
                            val handle = selected?.takeIf { handlesBelongToHit }?.let { item ->
                                val nearest = corners.minByOrNull {
                                    val point = viewportAtStart.worldToWindow(item.corner(it.xSign, it.ySign), size)
                                    (point.toOffset() - down.position).getDistance()
                                }
                                nearest?.takeIf {
                                    val point = viewportAtStart.worldToWindow(item.corner(it.xSign, it.ySign), size)
                                    val distance = (point.toOffset() - down.position).getDistance()
                                    distance <= handleRadius * 2f && distance < centerDistance
                                }
                            }
                            val rotationHit = selected?.takeIf { handlesBelongToHit }?.let {
                                val point = viewportAtStart.worldToWindow(rotationHandle(it, rotationGap / viewportAtStart.zoom), size)
                                val distance = (point.toOffset() - down.position).getDistance()
                                distance <= handleRadius * 2f && distance < centerDistance
                            } == true
                            val kind = when {
                                handle != null -> DragKind.Resize
                                rotationHit -> DragKind.Rotate
                                hit == null -> DragKind.Pan
                                hit.id == selectedId -> DragKind.Move
                                else -> DragKind.Pan
                            }
                            val initialItem = if (kind == DragKind.Resize || kind == DragKind.Rotate) selected else hit
                            var dragged = false
                            var viewportGesture = false
                            var totalDelta = Offset.Zero
                            var lastPosition = down.position
                            var upTime = down.uptimeMillis
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2 && (viewportGesture || !dragged || kind == DragKind.Pan)) {
                                    viewportGesture = true
                                    dragged = true
                                    lastTapId = null
                                    val focal = event.calculateCentroid(useCurrent = false)
                                    if (focal != Offset.Unspecified) {
                                        board.transformViewport(
                                            focal.toCanvasPoint(), event.calculatePan().toCanvasPoint(),
                                            event.calculateZoom(), canvasSize, floatingMode,
                                        )
                                    }
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                } else if (pressed >= 2) {
                                    // An established image drag keeps ownership until every finger lifts.
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                } else if (pressed == 1 && !viewportGesture) {
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val delta = change.positionChange()
                                    totalDelta += delta
                                    if (!dragged && totalDelta.getDistance() > viewConfiguration.touchSlop) {
                                        dragged = true
                                        lastTapId = null
                                    }
                                    if (dragged) {
                                        when (kind) {
                                            DragKind.Pan -> board.setViewport(viewportAtStart.pannedBy(totalDelta.toCanvasPoint()), floatingMode)
                                            DragKind.Move -> initialItem?.let {
                                                board.update(it.copy(center = it.center + totalDelta.toCanvasPoint() / viewportAtStart.zoom))
                                            }
                                            DragKind.Resize -> if (initialItem != null && handle != null) {
                                                board.update(initialItem.resizedAtCorner(handle.xSign, handle.ySign,
                                                    totalDelta.toCanvasPoint() / viewportAtStart.zoom))
                                            }
                                            DragKind.Rotate -> initialItem?.let {
                                                board.update(it.rotatedFrom(worldStart,
                                                    viewportAtStart.windowToWorld(change.position.toCanvasPoint(), size)))
                                            }
                                        }
                                        change.consume()
                                    }
                                    lastPosition = change.position
                                }
                                val up = event.changes.firstOrNull { it.id == down.id }
                                if (up != null) upTime = up.uptimeMillis
                            } while (event.changes.any { it.pressed })
                            // A handle affects drags only. Stationary taps always select the top image.
                            if (!dragged && !viewportGesture && (hit != null || (handle == null && !rotationHit))) {
                                selectedId = hit?.id
                                val elapsed = upTime - lastTapTime
                                if (hit != null && hit.id == lastTapId &&
                                    elapsed in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                                    (lastPosition - lastTapPosition).getDistance() <= viewConfiguration.touchSlop * 2f
                                ) {
                                    menuId = hit.id
                                    menuPosition = lastPosition
                                    lastTapId = null
                                } else {
                                    lastTapId = hit?.id
                                    lastTapTime = upTime
                                    lastTapPosition = lastPosition
                                }
                            }
                        } finally { board.save() }
                    }
                },
        ) {
            val viewport = board.viewportFor(floatingMode)
            val size = CanvasSize(this.size.width, this.size.height)
            for (item in board.items.inStackingOrder()) {
                if (!item.intersects(viewport, size, 24f * density)) continue
                val image = board.images[item.id] ?: continue
                val center = viewport.worldToWindow(item.center, size).toOffset()
                val width = item.width * viewport.zoom
                val height = item.height * viewport.zoom
                rotate(item.rotationDegrees, center) {
                    translate(center.x - width / 2f, center.y - height / 2f) {
                        drawImage(image, dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)))
                        if (item.id == selectedId) drawRect(selectionColor,
                            size = androidx.compose.ui.geometry.Size(width, height), style = Stroke(2.dp.toPx()))
                    }
                }
                if (item.id == selectedId) {
                    for (corner in corners) {
                        val point = viewport.worldToWindow(item.corner(corner.xSign, corner.ySign), size).toOffset()
                        drawCircle(Color.White, handleRadius, point)
                        drawCircle(selectionColor, handleRadius, point, style = Stroke(2.dp.toPx()))
                    }
                    val top = viewport.worldToWindow(item.localToWorld(CanvasPoint(0f, -item.height / 2f)), size).toOffset()
                    val rotation = viewport.worldToWindow(rotationHandle(item, rotationGap / viewport.zoom), size).toOffset()
                    drawLine(selectionColor, top, rotation, 2.dp.toPx())
                    drawCircle(selectionColor, handleRadius, rotation)
                }
            }
        }
        Box(Modifier.offset { IntOffset(menuPosition.x.roundToInt(), menuPosition.y.roundToInt()) }.size(1.dp)) {
            DropdownMenu(expanded = menuId != null, onDismissRequest = { menuId = null }) {
                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                    menuId?.let { board.delete(it) }
                    selectedId = null
                    menuId = null
                })
            }
        }
    }
}

private fun rotationHandle(item: BoardItem, gap: Float) =
    item.localToWorld(CanvasPoint(0f, -item.height / 2f - gap))

private fun Offset.toCanvasPoint() = CanvasPoint(x, y)
private fun CanvasPoint.toOffset() = Offset(x, y)
