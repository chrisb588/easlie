package com.chrisb588.easlie.images

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Trace
import android.util.Log
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
internal class ImageRenderer(private val scope: CoroutineScope, budget: Long, private val profile: Boolean = false) {
    private val cache = ByteImageCache<String, CachedImage>(budget) { it.bitmap.allocationByteCount.toLong() }
    private val decoder = Semaphore(1)
    private val jobs = mutableMapOf<String, Pair<Int, Job>>()
    private var requested = emptyMap<String, RenderRequest>()
    private val profileCounters = ImageProfileCounters()
    private var refreshes = 0L
    private var visibleCount = 0
    private var nearbyCount = 0
    var images by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
        private set

    fun refresh(items: List<BoardItem>, sources: Map<String, ImageSource>, viewport: CanvasViewport,
        size: CanvasSize, density: Float) {
        val visible = items.filter { it.intersects(viewport, size, 24f * density) }
        val nearby = items.filter { it !in visible && it.intersects(viewport, size, 128f * density) }
        visibleCount = visible.size
        nearbyCount = nearby.size
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
        if (profile) {
            val observed = cache.snapshot()
            demands.forEach { demand ->
                if (observed[demand.id]?.sample == demand.sample) profileCounters.recordHit()
                else profileCounters.recordMiss()
            }
        }
        startDecodes(sources)
        if (profile && ++refreshes % 100L == 0L) {
            logProfile()
        }
    }

    private fun startDecodes(sources: Map<String, ImageSource>) {
        // Free excess pixels before admitting new images or upgrading other entries.
        // Otherwise an old sharper tier can evict an unchanged visible image, which
        // has no decode queued and would stay blank until the next viewport change.
        val cached = cache.snapshot()
        val decodeOrder = requested.values.sortedBy { demand ->
            if ((cached[demand.id]?.sample ?: Int.MAX_VALUE) < demand.sample) 0 else 1
        }
        for (demand in decodeOrder) {
            val old = cache[demand.id]
            if (old?.sample == demand.sample || jobs[demand.id]?.first == demand.sample) continue
            if (profile) profileCounters.recordScheduled()
            val source = sources.getValue(demand.id)
            val job = scope.launch {
                var decoded: Bitmap? = null
                var admitted = false
                try {
                    var decodeMillis = 0L
                    // Queue on the main dispatcher so IO scheduling cannot reorder downsizes.
                    val bitmap = decoder.withPermit {
                        withContext(Dispatchers.IO) {
                            val start = if (profile) SystemClock.elapsedRealtimeNanos() else 0L
                            if (profile) Trace.beginSection("easlie.decode sample=${demand.sample} source_edge=${source.edge}")
                            try {
                                source.decode(demand.sample).also {
                                    decoded = it
                                    if (profile) decodeMillis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                                }
                            } finally {
                                if (profile) Trace.endSection()
                            }
                        }
                    }
                    if (profile) Log.d("EaslieImageProfile", "decode_ms=$decodeMillis sample=${demand.sample} bytes=${bitmap.allocationByteCount} source_edge=${source.edge}")
                    if (requested[demand.id]?.sample == demand.sample) {
                        admitted = cache.put(demand.id, CachedImage(demand.sample, bitmap, bitmap.asImageBitmap()),
                            allowProtectedEviction = false)
                        if (cache[demand.id]?.bitmap === bitmap) {
                            decoded = null // Cache owns it; Compose may still reference an evicted bitmap.
                        }
                        publish()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (profile) Log.w("EaslieImageProfile", "decode_failed sample=${demand.sample}", failure)
                    // Keep the existing image if either an upgrade or downsize fails.
                } finally {
                    decoded?.recycle() // Includes cancellation while returning from the IO dispatcher.
                    if (jobs[demand.id]?.second === currentCoroutineContext().job) jobs.remove(demand.id)
                    // An admission deferred behind an oversized old tier gets another chance
                    // as soon as a downsize frees room, without waiting for a user gesture.
                    if (admitted) startDecodes(sources)
                }
            }
            jobs[demand.id] = demand.sample to job
        }
    }

    /** Capture short workloads too; periodic logging alone can miss their final counters. */
    fun logProfile() {
        if (profile) Log.d("EaslieImageProfile", "refreshes=$refreshes ${profileCounters.snapshot()} cache_bytes=${cache.sizeBytes} budget_bytes=${cache.budget} visible=$visibleCount nearby=$nearbyCount")
    }

    fun remove(id: String) {
        jobs.remove(id)?.second?.cancel()
        requested = requested - id
        cache.remove(id)
        publish()
    }

    fun clear() {
        val pending = jobs.values.map { it.second }
        jobs.clear()
        requested = emptyMap()
        // Main-dispatcher cancellation can run finally immediately. Detach jobs
        // and demands first so cleanup cannot mutate this iteration or retry work.
        pending.forEach { it.cancel() }
        cache.snapshot().keys.forEach { cache.remove(it) }
        publish()
    }

    private fun publish() { images = cache.snapshot().mapValues { it.value.image } }
}
