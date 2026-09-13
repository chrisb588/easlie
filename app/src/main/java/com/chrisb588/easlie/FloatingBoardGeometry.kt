package com.chrisb588.easlie

/** Pure sizing logic used by the overlay resize handle and its JVM tests. */
internal object FloatingBoardGeometry {
    fun resizedDimensions(
        initialWidth: Int,
        initialHeight: Int,
        deltaX: Int,
        deltaY: Int,
        minimumWidth: Int,
        minimumHeight: Int
    ): ResizedDimensions {
        return ResizedDimensions(
            width = (initialWidth + deltaX).coerceAtLeast(minimumWidth),
            height = (initialHeight + deltaY).coerceAtLeast(minimumHeight)
        )
    }
}

internal data class ResizedDimensions(val width: Int, val height: Int)
