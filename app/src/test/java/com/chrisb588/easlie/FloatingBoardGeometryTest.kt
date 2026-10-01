package com.chrisb588.easlie

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingBoardGeometryTest {
    @Test
    fun leftResizeKeepsTheRightEdgeFixed() {
        val resized = FloatingBoardGeometry.resizedFromLeft(
            100, 360, 260, -50, 40, 280, 200, 500
        )
        assertEquals(50, resized.x)
        assertEquals(410, resized.width)
        assertEquals(460, resized.x + resized.width)
        assertEquals(300, resized.height)
    }

    @Test
    fun leftResizeStopsAtDisplayEdgeAndMinimumSize() {
        val expanded = FloatingBoardGeometry.resizedFromLeft(
            100, 360, 260, -2000, 2000, 280, 200, 500
        )
        assertEquals(0, expanded.x)
        assertEquals(460, expanded.width)
        assertEquals(500, expanded.height)
        val shrunk = FloatingBoardGeometry.resizedFromLeft(
            100, 360, 260, 2000, -2000, 280, 200, 500
        )
        assertEquals(180, shrunk.x)
        assertEquals(280, shrunk.width)
        assertEquals(460, shrunk.x + shrunk.width)
        assertEquals(200, shrunk.height)
    }

    @Test
    fun resizeCannotGrowBeyondSpaceRemainingAtTheAnchor() {
        val resized = FloatingBoardGeometry.resizedDimensions(
            360, 260, 2000, 2000, 280, 200, 420, 310
        )
        assertEquals(420, resized.width)
        assertEquals(310, resized.height)
    }

    @Test
    fun viewportSmallerThanMinimumStillContainsTheBoard() {
        val resized = FloatingBoardGeometry.resizedDimensions(
            360, 260, -2000, -2000, 280, 200, 240, 160
        )
        assertEquals(240, resized.width)
        assertEquals(160, resized.height)
    }

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
