package com.chrisb588.easlie.images

import com.chrisb588.easlie.canvas.BoardItem
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.canvas.CanvasViewport
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Window coordinates already include device density; do not multiply them by density again. */
internal fun BoardItem.intersects(viewport: CanvasViewport, size: CanvasSize, margin: Float): Boolean {
    val center = viewport.worldToWindow(center, size)
    val angle = Math.toRadians(rotationDegrees.toDouble())
    val halfWidth = (abs(cos(angle)) * width + abs(sin(angle)) * height) * viewport.zoom / 2
    val halfHeight = (abs(sin(angle)) * width + abs(cos(angle)) * height) * viewport.zoom / 2
    return center.x + halfWidth >= -margin && center.x - halfWidth <= size.width + margin &&
        center.y + halfHeight >= -margin && center.y - halfHeight <= size.height + margin
}

/** Power-of-two subsampling with a dead band when releasing a sharper copy. */
internal fun renderSample(sourceEdge: Int, screenEdge: Float, previous: Int? = null): Int {
    val target = screenEdge.coerceAtLeast(1f)
    var sample = 1
    while (sample <= sourceEdge / 2 && sourceEdge / (sample * 2) >= target) sample *= 2
    if (previous != null && sample > previous && target > sourceEdge.toFloat() / previous * 0.35f) {
        return previous
    }
    return sample
}

/** Access-order eviction, with off-screen entries evicted before visible entries. */
internal class ByteImageCache<K, V>(val budget: Long, private val bytes: (V) -> Long) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    var protectedKeys: Set<K> = emptySet()
    var sizeBytes = 0L
        private set
    operator fun get(key: K): V? = entries[key]
    fun snapshot(): Map<K, V> = entries.toMap()
    fun remove(key: K) {
        entries.remove(key)?.let { sizeBytes -= bytes(it) }
    }
    fun put(key: K, value: V) {
        if (bytes(value) > budget) return
        remove(key)
        entries[key] = value
        sizeBytes += bytes(value)
        while (sizeBytes > budget) {
            remove(entries.keys.firstOrNull { it !in protectedKeys } ?: entries.keys.first())
        }
    }
}

internal data class ResolutionTier(val sample: Int, val screenEdge: Float, val budgetLimited: Boolean)

/** After memory pressure lowers a tier, require meaningful size growth before upgrading again. */
internal fun constrainTier(width: Int, height: Int, desired: Int, screenEdge: Float,
    budget: Long, previous: ResolutionTier?): ResolutionTier {
    var sample = desired
    while (kotlin.math.ceil(width.toDouble() / sample) * kotlin.math.ceil(height.toDouble() / sample) * 4 > budget &&
        sample < maxOf(width, height)) sample *= 2
    if (previous?.budgetLimited == true && sample < previous.sample && screenEdge <= previous.screenEdge * 1.2f) {
        return previous
    }
    if (previous != null && sample == previous.sample) return previous
    return ResolutionTier(sample, screenEdge, sample > desired)
}
