package com.chrisb588.easlie

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingBoardGeometryTest {
    @Test
    fun resizeGrowsByThePointerDelta() {
        val resized = FloatingBoardGeometry.resizedDimensions(
            initialWidth = 360,
            initialHeight = 260,
            deltaX = 80,
            deltaY = 40,
            minimumWidth = 280,
            minimumHeight = 200
        )

        assertEquals(440, resized.width)
        assertEquals(300, resized.height)
    }

    @Test
    fun resizeClampsToThePracticalMinimum() {
        val resized = FloatingBoardGeometry.resizedDimensions(
            initialWidth = 360,
            initialHeight = 260,
            deltaX = -200,
            deltaY = -200,
            minimumWidth = 280,
            minimumHeight = 200
        )

        assertEquals(280, resized.width)
        assertEquals(200, resized.height)
    }
}
