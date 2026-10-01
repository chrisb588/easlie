package com.chrisb588.easlie.images

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.chrisb588.easlie.canvas.BoardItem
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.canvas.CanvasViewport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class RenderRequest(val id: String, val tier: ResolutionTier) {
    val sample get() = tier.sample
}
private data class CachedImage(val sample: Int, val bitmap: Bitmap, val image: ImageBitmap)

/** Main-thread ownership; only file decoding runs on IO. One decode bounds transient allocations. */
internal class ImageRenderer(private val scope: CoroutineScope, budget: Long) {
    private val cache = ByteImageCache<String, CachedImage>(budget) { it.bitmap.allocationByteCount.toLong() }
    private val decoder = Semaphore(1)
    private val jobs = mutableMapOf<String, Pair<Int, Job>>()
    private var requested = emptyMap<String, RenderRequest>()
    var images by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
        private set

    fun refresh(items: List<BoardItem>, sources: Map<String, ImageSource>, viewport: CanvasViewport,
        size: CanvasSize, density: Float) {
        val visible = items.filter { it.intersects(viewport, size, 24f * density) }
        val nearby = items.filter { it !in visible && it.intersects(viewport, size, 128f * density) }
        val demands = (visible + nearby).mapNotNull { item ->
            val source = sources[item.id] ?: return@mapNotNull null
            val onScreen = item in visible
            // Nearby entries have a separate reserve, so entering the margin cannot change visible tiers.
            val share = if (onScreen) cache.budget * 7 / 8 / maxOf(1, visible.size)
                else cache.budget / 8 / maxOf(1, nearby.size)
            val previous = requested[item.id]?.tier
            val edge = if (onScreen) maxOf(item.width, item.height) * viewport.zoom else 128f * density
            val desired = renderSample(source.edge, edge, previous?.sample)
            val tier = constrainTier(source.width, source.height, desired, edge, share, previous)
            RenderRequest(item.id, tier)
        }
        requested = demands.associateBy { it.id }
        jobs.keys.toList().forEach { id ->
            if (requested[id]?.sample != jobs[id]?.first) jobs.remove(id)?.second?.cancel()
        }
        cache.protectedKeys = visible.map { it.id }.toSet()
        cache.snapshot().keys.forEach { id ->
            // Keep either resolution until its replacement is ready. Removing a sharper
            // copy before a downsize finishes makes zooming and resizing flash blank.
            if (id !in requested) cache.remove(id)
        }
        publish()
        for (demand in demands) {
            val old = cache[demand.id]
            if (old?.sample == demand.sample || jobs[demand.id]?.first == demand.sample) continue
            val source = sources.getValue(demand.id)
            val job = scope.launch {
                var decoded: Bitmap? = null
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        decoder.withPermit { source.decode(demand.sample).also { decoded = it } }
                    }
                    if (requested[demand.id]?.sample == demand.sample) {
                        cache.put(demand.id, CachedImage(demand.sample, bitmap, bitmap.asImageBitmap()))
                        if (cache[demand.id]?.bitmap === bitmap) {
                            decoded = null // Cache owns it; Compose may still reference an evicted bitmap.
                        }
                        publish()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the existing image if either an upgrade or downsize fails.
                } finally {
                    decoded?.recycle() // Includes cancellation while returning from the IO dispatcher.
                    if (jobs[demand.id]?.second === currentCoroutineContext().job) jobs.remove(demand.id)
                }
            }
            jobs[demand.id] = demand.sample to job
        }
    }

    fun remove(id: String) {
        jobs.remove(id)?.second?.cancel()
        requested = requested - id
        cache.remove(id)
        publish()
    }

    fun clear() {
        jobs.values.forEach { it.second.cancel() }
        jobs.clear()
        requested = emptyMap()
        cache.snapshot().keys.forEach { cache.remove(it) }
        publish()
    }

    private fun publish() { images = cache.snapshot().mapValues { it.value.image } }
}
