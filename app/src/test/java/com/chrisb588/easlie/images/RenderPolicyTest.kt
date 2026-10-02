package com.chrisb588.easlie.images

import com.chrisb588.easlie.canvas.*
import org.junit.Assert.*
import org.junit.Test

class RenderPolicyTest {
    @Test fun samplesFollowPixelsAndHaveADowngradeDeadBand() {
        assertEquals(8, renderSample(4096, 500f))
        assertEquals(4, renderSample(4096, 520f, 8))
        assertEquals(4, renderSample(4096, 490f, 4))
        assertEquals(8, renderSample(4096, 350f, 4))
        assertEquals(1, renderSample(4096, 9000f))
    }

    @Test fun memoryPressureDoesNotOscillateWhenVisibleCountChanges() {
        val first = constrainTier(2048, 2048, 2, 1024f, 7L * 1024 * 1024, null)
        assertEquals(2, first.sample)
        val crowded = constrainTier(2048, 2048, 2, 1024f, 3500000L, first)
        assertEquals(4, crowded.sample)
        assertEquals(4, constrainTier(2048, 2048, 2, 1024f, 7L * 1024 * 1024, crowded).sample)
        assertEquals(1, constrainTier(2048, 2048, 1, 1300f, 20L * 1024 * 1024, crowded).sample)
    }

    @Test fun rotatedBoundsAndSafetyMarginAreIncluded() {
        val viewport = CanvasViewport()
        val size = CanvasSize(100f, 100f)
        val item = BoardItem("a", CanvasPoint(70f, 0f), 100f, 10f, 90f, 0)
        assertFalse(item.intersects(viewport, size, 0f))
        assertTrue(item.intersects(viewport, size, 24f))
        assertTrue(item.copy(rotationDegrees = 0f).intersects(viewport, size, 0f))
        assertFalse(item.copy(center = CanvasPoint(1000f, 0f)).intersects(viewport, size, 24f))
    }

    @Test fun deferredAdmissionKeepsVisibleImagesUntilSpaceIsFreed() {
        val cache = ByteImageCache<String, Int>(100) { it.toLong() }
        cache.put("unchanged", 20)
        cache.put("downsizing", 70)
        cache.protectedKeys = setOf("unchanged", "downsizing", "new")
        assertFalse(cache.put("new", 20, allowProtectedEviction = false))
        assertEquals(setOf("unchanged", "downsizing"), cache.snapshot().keys)
        assertTrue(cache.put("downsizing", 20, allowProtectedEviction = false))
        assertTrue(cache.put("new", 20, allowProtectedEviction = false))
        assertEquals(setOf("unchanged", "downsizing", "new"), cache.snapshot().keys)
        assertTrue(cache.sizeBytes <= cache.budget)
    }

    @Test fun cacheUsesBytesAccessOrderAndVisiblePriority() {
        val cache = ByteImageCache<String, Int>(10) { it.toLong() }
        cache.put("visible", 4)
        cache.put("near", 4)
        cache.protectedKeys = setOf("visible")
        cache.put("new", 4)
        assertNull(cache["near"])
        assertEquals(4, cache["visible"])
        cache.protectedKeys = emptySet()
        cache.put("last", 4)
        assertNull(cache["new"])
        assertTrue(cache.sizeBytes <= cache.budget)
        cache.put("oversized", 11)
        assertNull(cache["oversized"])
        cache.put("last", 11)
        assertEquals(4, cache["last"])
        assertTrue(cache.sizeBytes <= cache.budget)
        cache.remove("visible")
        assertEquals(4L, cache.sizeBytes)
    }
}
