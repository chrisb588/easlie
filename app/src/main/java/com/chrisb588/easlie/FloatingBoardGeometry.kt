package com.chrisb588.easlie

/** Pure sizing logic used by the overlay resize handle and its JVM tests. */
internal object FloatingBoardGeometry {
    fun resizedFromLeft(
        initialX: Int,
        initialWidth: Int,
        initialHeight: Int,
        deltaX: Int,
        deltaY: Int,
        minimumWidth: Int,
        minimumHeight: Int,
        maximumHeight: Int
    ): LeftResize {
        val right = initialX + initialWidth
        val size = resizedDimensions(
            initialWidth, initialHeight, -deltaX, deltaY,
            minimumWidth, minimumHeight, right, maximumHeight
        )
        return LeftResize(right - size.width, size.width, size.height)
    }

    fun resizedDimensions(
        initialWidth: Int,
        initialHeight: Int,
        deltaX: Int,
        deltaY: Int,
        minimumWidth: Int,
        minimumHeight: Int,
        maximumWidth: Int = Int.MAX_VALUE,
        maximumHeight: Int = Int.MAX_VALUE
    ): ResizedDimensions {
        return ResizedDimensions(
            width = (initialWidth + deltaX).coerceIn(minimumWidth.coerceAtMost(maximumWidth), maximumWidth),
            height = (initialHeight + deltaY).coerceIn(minimumHeight.coerceAtMost(maximumHeight), maximumHeight)
        )
    }
}

internal data class ResizedDimensions(val width: Int, val height: Int)

internal data class LeftResize(val x: Int, val width: Int, val height: Int)
