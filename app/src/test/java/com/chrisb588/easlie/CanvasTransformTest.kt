package com.chrisb588.easlie

import com.chrisb588.easlie.canvas.CanvasPoint
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.canvas.CanvasViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasTransformTest {
    private val windowSize = CanvasSize(width = 1000f, height = 800f)

    @Test
    fun worldAndWindowConversionsAreInverses() {
        val viewport = CanvasViewport(center = CanvasPoint(120f, -80f), zoom = 2.5f)
        val worldPoint = CanvasPoint(-30f, 45f)

        val windowPoint = viewport.worldToWindow(worldPoint, windowSize)

        assertPointEquals(worldPoint, viewport.windowToWorld(windowPoint, windowSize))
    }

    @Test
    fun panningMovesTheWorldWithTheDrag() {
        val viewport = CanvasViewport(center = CanvasPoint(10f, 20f), zoom = 2f)
        val drag = CanvasPoint(40f, -20f)

        val pannedViewport = viewport.pannedBy(drag)

        assertPointEquals(CanvasPoint(-10f, 30f), pannedViewport.center)
        assertPointEquals(
            viewport.worldToWindow(CanvasPoint(10f, 20f), windowSize) + drag,
            pannedViewport.worldToWindow(CanvasPoint(10f, 20f), windowSize),
        )
    }

    @Test
    fun zoomAroundFocalPointKeepsTheWorldPointStationary() {
        val viewport = CanvasViewport(center = CanvasPoint(120f, -80f), zoom = 1.5f)
        val focalPoint = CanvasPoint(725f, 210f)
        val worldPointAtFocalPoint = viewport.windowToWorld(focalPoint, windowSize)

        val zoomedViewport = viewport.transformedBy(
            focalPoint = focalPoint,
            pan = CanvasPoint(0f, 0f),
            zoomChange = 2f,
            windowSize = windowSize,
        )

        assertPointEquals(
            worldPointAtFocalPoint,
            zoomedViewport.windowToWorld(focalPoint, windowSize),
        )
        assertEquals(3f, zoomedViewport.zoom, EPSILON)
    }

    @Test
    fun combinedPanAndZoomMovesTheFocalPointByThePan() {
        val viewport = CanvasViewport(center = CanvasPoint(40f, 30f), zoom = 2f)
        val focalPoint = CanvasPoint(300f, 500f)
        val pan = CanvasPoint(24f, -16f)
        val worldPointAtFocalPoint = viewport.windowToWorld(focalPoint, windowSize)

        val transformedViewport = viewport.transformedBy(
            focalPoint = focalPoint,
            pan = pan,
            zoomChange = 1.5f,
            windowSize = windowSize,
        )

        assertPointEquals(
            focalPoint + pan,
            transformedViewport.worldToWindow(worldPointAtFocalPoint, windowSize),
        )
    }

    @Test
    fun repeatedZoomStaysWithinTheSupportedRange() {
        val focalPoint = CanvasPoint(500f, 400f)
        var viewport = CanvasViewport()

        repeat(30) {
            viewport = viewport.transformedBy(
                focalPoint = focalPoint,
                pan = CanvasPoint(0f, 0f),
                zoomChange = 2f,
                windowSize = windowSize,
            )
        }
        assertEquals(CanvasViewport.MAX_ZOOM, viewport.zoom, EPSILON)

        repeat(60) {
            viewport = viewport.transformedBy(
                focalPoint = focalPoint,
                pan = CanvasPoint(0f, 0f),
                zoomChange = 0.5f,
                windowSize = windowSize,
            )
        }
        assertEquals(CanvasViewport.MIN_ZOOM, viewport.zoom, EPSILON)
        assertTrue(viewport.zoom > 0f)
    }

    private fun assertPointEquals(expected: CanvasPoint, actual: CanvasPoint) {
        assertEquals(expected.x, actual.x, EPSILON)
        assertEquals(expected.y, actual.y, EPSILON)
    }

    private companion object {
        const val EPSILON = 0.0001f
    }
}
