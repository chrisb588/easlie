package com.chrisb588.easlie.canvas

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Persistent image geometry. The center and dimensions are in world coordinates. */
data class BoardItem(
    val id: String,
    val center: CanvasPoint,
    val width: Float,
    val height: Float,
    val rotationDegrees: Float = 0f,
    val zIndex: Int,
    val assetId: String = id,
) {
    fun localToWorld(point: CanvasPoint) = center + point.rotatedBy(rotationDegrees)

    fun contains(point: CanvasPoint): Boolean {
        val local = (point - center).rotatedBy(-rotationDegrees)
        return abs(local.x) <= width / 2f && abs(local.y) <= height / 2f
    }

    fun corner(xSign: Float, ySign: Float) =
        localToWorld(CanvasPoint(xSign * width / 2f, ySign * height / 2f))

    /** Keep the opposite corner fixed and project the drag onto the aspect-ratio diagonal. */
    fun resizedAtCorner(xSign: Float, ySign: Float, delta: CanvasPoint): BoardItem {
        val diagonal = CanvasPoint(xSign * width, ySign * height)
        val localDelta = delta.rotatedBy(-rotationDegrees)
        val scale = (1f + (localDelta.x * diagonal.x + localDelta.y * diagonal.y) /
            (width * width + height * height)).coerceAtLeast(16f / min(width, height))
        val anchor = corner(-xSign, -ySign)
        return copy(
            center = anchor + (diagonal * (scale / 2f)).rotatedBy(rotationDegrees),
            width = width * scale,
            height = height * scale,
        )
    }

    /** Scale from the transition geometry without moving its center or changing its angle. */
    fun resizedAroundCenter(distanceScale: Float): BoardItem {
        val scale = distanceScale.coerceAtLeast(16f / min(width, height))
        return copy(width = width * scale, height = height * scale)
    }

    fun rotatedFrom(start: CanvasPoint, end: CanvasPoint): BoardItem {
        val first = start - center
        val last = end - center
        val angle = atan2(last.y, last.x) - atan2(first.y, first.x)
        return copy(rotationDegrees = (rotationDegrees + Math.toDegrees(angle.toDouble()).toFloat()) % 360f)
    }
}

fun CanvasPoint.rotatedBy(degrees: Float): CanvasPoint {
    val radians = Math.toRadians(degrees.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    return CanvasPoint(x * cosine - y * sine, x * sine + y * cosine)
}

/** Stable sorting means later items win ties, including duplicate z indexes. */
fun List<BoardItem>.inStackingOrder() = sortedBy { it.zIndex }

fun List<BoardItem>.hitTest(point: CanvasPoint) = inStackingOrder().lastOrNull { it.contains(point) }

/** Start each diagonal at the viewport center again when its next center would leave the window. */
fun importCenters(count: Int, viewport: CanvasViewport, size: CanvasSize): List<CanvasPoint> {
    val step = min(size.width, size.height) * 0.1f
    var next = size.center
    return List(count) {
        val result = viewport.windowToWorld(next, size)
        next += CanvasPoint(step, step)
        if (next.x > size.width || next.y > size.height) next = size.center
        result
    }
}
