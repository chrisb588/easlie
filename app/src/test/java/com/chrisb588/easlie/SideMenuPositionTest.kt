package com.chrisb588.easlie

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class SideMenuPositionTest {
    private val position = SideMenuPositionProvider(gap = 8)

    @Test fun menuAppearsToTheLeftAndAlignsWithButtonTop() {
        assertEquals(IntOffset(672, 16), position.calculatePosition(
            IntRect(944, 16, 992, 64), IntSize(1008, 800), LayoutDirection.Ltr, IntSize(264, 208)))
    }

    @Test fun lowerAnchorKeepsTheMenuInsideTheWindow() {
        assertEquals(IntOffset(72, 152), position.calculatePosition(
            IntRect(344, 300, 392, 348), IntSize(408, 360), LayoutDirection.Ltr, IntSize(264, 208)))
    }

    @Test fun narrowWindowClampsToLeftEdge() {
        assertEquals(IntOffset(0, 16), position.calculatePosition(
            IntRect(200, 16, 248, 64), IntSize(264, 400), LayoutDirection.Rtl, IntSize(240, 208)))
    }
}
