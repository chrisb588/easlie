package com.chrisb588.easlie

import com.chrisb588.easlie.canvas.BoardItem
import com.chrisb588.easlie.canvas.CanvasPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class CenteredResizeTest {
    @Test fun distanceScalingPreservesCenterAspectAndRotation() {
        val item = BoardItem("image", CanvasPoint(40f, 80f), 200f, 100f, 37f, 0)
        val resized = item.resizedAroundCenter(1.75f)
        assertEquals(item.center, resized.center)
        assertEquals(item.rotationDegrees, resized.rotationDegrees)
        assertEquals(350f, resized.width)
        assertEquals(175f, resized.height)
        val minimum = item.resizedAroundCenter(0f)
        assertEquals(16f, minimum.height)
        assertEquals(2f, minimum.width / minimum.height)
    }
}
