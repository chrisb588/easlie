package com.chrisb588.easlie

import com.chrisb588.easlie.canvas.*
import org.junit.Assert.*
import org.junit.Test

class BoardItemTest {
    private val size = CanvasSize(1000f, 800f)
    private val item = BoardItem("first", CanvasPoint(10f, 20f), 200f, 100f, zIndex = 10)

    @Test
    fun diagonalUsesTenPercentOfShortEdgeAndWrapsAtVisibleBoundary() {
        val viewport = CanvasViewport(CanvasPoint(200f, -100f), 2f)
        val centers = importCenters(8, viewport, size)
        assertPoint(viewport.center, centers[0])
        assertPoint(CanvasPoint(240f, -60f), centers[1])
        assertPoint(CanvasPoint(360f, 60f), centers[4])
        assertPoint(CanvasPoint(400f, 100f), centers[5])
        assertPoint(viewport.center, centers[6])
        centers.forEach {
            val screen = viewport.worldToWindow(it, size)
            assertTrue(screen.x in 0f..size.width && screen.y in 0f..size.height)
        }
    }

    @Test
    fun stackingAndHitTestUseHighestIndexThenLaterDuplicate() {
        val top = item.copy(id = "top", zIndex = 30)
        val duplicate = item.copy(id = "duplicate", zIndex = 30)
        val outside = top.copy(id = "outside", center = CanvasPoint(500f, 500f), zIndex = 40)
        val items = listOf(top, item, duplicate, outside)
        assertEquals(listOf("first", "top", "duplicate", "outside"), items.inStackingOrder().map { it.id })
        assertEquals("duplicate", items.hitTest(item.center)?.id)
        assertNull(items.hitTest(CanvasPoint(-500f, -500f)))
    }

    @Test
    fun hitTestingUsesRotatedRectangleInsteadOfItsAxisAlignedBoundingBox() {
        val rotated = item.copy(rotationDegrees = 90f)
        assertTrue(rotated.contains(CanvasPoint(10f, 110f)))
        assertFalse(rotated.contains(CanvasPoint(100f, 20f)))
        assertTrue(rotated.contains(rotated.corner(1f, 1f)))
    }

    @Test
    fun everyResizeHandlePreservesAspectRatioAndOppositeCornerAfterRotation() {
        val rotated = item.copy(rotationDegrees = 37f)
        for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) {
            val resized = rotated.resizedAtCorner(x, y, CanvasPoint(x * 50f, y * 25f).rotatedBy(37f))
            assertEquals(2f, resized.width / resized.height, 0.0001f)
            assertEquals(250f, resized.width, 0.001f)
            assertPoint(rotated.corner(-x, -y), resized.corner(-x, -y))
        }
    }

    @Test
    fun resizeCannotFlipOrCollapseTheImage() {
        val resized = item.resizedAtCorner(1f, 1f, CanvasPoint(-1000f, -1000f))
        assertEquals(16f, resized.height, 0.0001f)
        assertEquals(2f, resized.width / resized.height, 0.0001f)
        assertPoint(item.corner(-1f, -1f), resized.corner(-1f, -1f))
    }

    @Test
    fun rotationUsesAngleAroundCenterWithoutMovingOrResizing() {
        val rotated = item.rotatedFrom(item.center + CanvasPoint(0f, -100f), item.center + CanvasPoint(100f, 0f))
        assertEquals(90f, rotated.rotationDegrees, 0.0001f)
        assertEquals(item.center, rotated.center)
        assertEquals(item.width, rotated.width)
        assertEquals(item.height, rotated.height)
    }

    private fun assertPoint(expected: CanvasPoint, actual: CanvasPoint) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
    }
}
