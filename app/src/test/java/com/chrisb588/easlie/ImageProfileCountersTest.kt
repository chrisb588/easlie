package com.chrisb588.easlie

import com.chrisb588.easlie.images.ImageProfileCounters
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageProfileCountersTest {
    @Test fun cumulativeSnapshotsKeepUnitsStable() {
        val counters = ImageProfileCounters()
        assertEquals("refresh_hits=0 refresh_misses=0 decodes_scheduled=0", counters.snapshot())
        counters.recordHit()
        counters.recordMiss()
        counters.recordMiss()
        counters.recordScheduled()
        assertEquals("refresh_hits=1 refresh_misses=2 decodes_scheduled=1", counters.snapshot())
    }
}
