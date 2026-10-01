package com.chrisb588.easlie.canvas

import kotlin.math.max
import kotlin.math.min

/** A point in the board's world coordinate system or the window coordinate system. */
data class CanvasPoint(
    val x: Float,
    val y: Float,
) {
    operator fun plus(other: CanvasPoint) = CanvasPoint(x + other.x, y + other.y)

    operator fun minus(other: CanvasPoint) = CanvasPoint(x - other.x, y - other.y)

    operator fun times(value: Float) = CanvasPoint(x * value, y * value)

    operator fun div(value: Float) = CanvasPoint(x / value, y / value)
}

data class CanvasSize(
    val width: Float,
    val height: Float,
) {
    init {
        require(width >= 0f) { "Canvas width cannot be negative" }
        require(height >= 0f) { "Canvas height cannot be negative" }
    }

    val center: CanvasPoint
        get() = CanvasPoint(width / 2f, height / 2f)
}

/**
 * The viewport transform used by both rendering and gesture handling.
 *
 * [center] is the world-space point at the center of the window. [zoom] is the number of
 * window pixels represented by one world unit.
 */
data class CanvasViewport(
    val center: CanvasPoint = CanvasPoint(0f, 0f),
    val zoom: Float = 1f,
) {
    init {
        require(zoom > 0f) { "Viewport zoom must be positive" }
    }

    fun worldToWindow(worldPoint: CanvasPoint, windowSize: CanvasSize): CanvasPoint {
        return (worldPoint - center) * zoom + windowSize.center
    }

    fun windowToWorld(windowPoint: CanvasPoint, windowSize: CanvasSize): CanvasPoint {
        return (windowPoint - windowSize.center) / zoom + center
    }

    /** Moves the world in the same direction as a drag in window pixels. */
    fun pannedBy(windowDelta: CanvasPoint): CanvasViewport {
        return copy(center = center - windowDelta / zoom)
    }

    /**
     * Applies one transform-gesture update.
     *
     * The world point below [focalPoint] is kept below the focal point after the scale and pan
     * are applied. This is the same rule for a one-finger pan and a two-finger pinch, so gesture
     * handling does not need a second transform implementation.
     */
    fun transformedBy(
        focalPoint: CanvasPoint,
        pan: CanvasPoint,
        zoomChange: Float,
        windowSize: CanvasSize,
    ): CanvasViewport {
        require(zoomChange > 0f) { "Gesture zoom must be positive" }

        val worldPointAtFocalPoint = windowToWorld(focalPoint, windowSize)
        val nextZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val nextCenter = worldPointAtFocalPoint -
            (focalPoint + pan - windowSize.center) / nextZoom

        return CanvasViewport(center = nextCenter, zoom = nextZoom)
    }

    companion object {
        const val MIN_ZOOM = 0.1f
        const val MAX_ZOOM = 8f
    }
}
