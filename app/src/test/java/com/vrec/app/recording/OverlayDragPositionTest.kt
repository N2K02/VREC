package com.vrec.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDragPositionTest {
    @Test fun dragClampsAtLeftAndTop() {
        assertEquals(OverlayDragPosition.Position(0, 0),
            OverlayDragPosition.clamp(-40, -60, 1080, 2400, 240, 60))
    }

    @Test fun dragClampsAtRightAndBottom() {
        assertEquals(OverlayDragPosition.Position(840, 2340),
            OverlayDragPosition.clamp(2000, 3000, 1080, 2400, 240, 60))
    }

    @Test fun dragPreservesPositionInsideBounds() {
        assertEquals(OverlayDragPosition.Position(234, 567),
            OverlayDragPosition.clamp(234, 567, 1080, 2400, 240, 60))
    }

    @Test fun rotatedOrSmallDisplayClampsToZero() {
        assertEquals(OverlayDragPosition.Position(0, 0),
            OverlayDragPosition.clamp(300, 500, 180, 50, 240, 60))
    }
}
