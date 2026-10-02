package com.chrisb588.easlie

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.chrisb588.easlie.canvas.*
import com.chrisb588.easlie.images.ImageRenderer
import com.chrisb588.easlie.images.ImageSource
import com.chrisb588.easlie.images.copyImageSource
import com.chrisb588.easlie.images.readImageSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EaslieApplication : Application() {
    val board by lazy {
        val memoryClass = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        BoardStore(File(filesDir, "board"), memoryClass.toLong() * 1024 * 1024 / 8)
    }
}

/** One authoritative board. All edits and disk snapshots pass through the same mutex. */
class BoardStore internal constructor(directory: File? = null, cacheBudget: Long = 16L * 1024 * 1024) {
    var items by mutableStateOf<List<BoardItem>>(emptyList())
        private set
    val images: Map<String, ImageBitmap> get() = renderer.images
    private var fullScreen by mutableStateOf(CanvasViewport())
    private var floating by mutableStateOf(CanvasViewport())
    var viewport: CanvasViewport
        get() = fullScreen
        set(value) { setViewport(value, floatingMode = false) }

    fun viewportFor(floatingMode: Boolean): CanvasViewport = if (floatingMode) floating else fullScreen

    fun setViewport(value: CanvasViewport, floatingMode: Boolean) = edit {
        if (floatingMode) floating = value else fullScreen = value
    }
    var windowSize by mutableStateOf(CanvasSize(0f, 0f))
    var message by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
        private set

    private val storage = directory?.let { BoardStorage(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = ImageRenderer(scope, cacheBudget)
    private val sources = mutableMapOf<String, ImageSource>()
    private val canvasOwners = mutableListOf<Any>()
    private var activeCanvasOwner by mutableStateOf<Any?>(null)
    private val mutex = Mutex()
    private val pending = ArrayDeque<Pair<ContentResolver, List<Uri>>>()
    private var writable = true
    private var dirty = false
    private var ready = storage == null
    private var reconciliationReferences = emptySet<String>()

    init {
        scope.launch {
            mutex.withLock {
                try {
                    val restored = withContext(Dispatchers.IO) { storage?.load() }
                    if (restored != null) {
                        fullScreen = restored.snapshot.fullScreen
                        floating = restored.snapshot.floating
                        var unreadable = 0
                        val restoredItems = mutableListOf<BoardItem>()
                        for (item in restored.snapshot.items) {
                            try {
                                val source = withContext(Dispatchers.IO) { readImageSource(storage!!.asset(item.assetId)) }
                                sources[item.id] = source
                                restoredItems.add(item)
                            } catch (failure: Exception) {
                                unreadable++
                                Log.w("BoardStore", "Cannot decode restored asset ${item.assetId}", failure)
                            }
                        }
                        items = restoredItems
                        val skipped = restored.skipped + unreadable
                        if (skipped > 0) message = "$skipped item(s) could not be restored: " +
                            "${restored.missing} missing asset(s), ${restored.malformed} malformed item(s), " +
                            "$unreadable unreadable image(s)."
                        // A later background scan must also preserve referenced but undecodable assets.
                        reconciliationReferences = restored.referencedAssets
                    }
                } catch (failure: Exception) {
                    writable = false
                    message = "Board could not be loaded. Storage is read-only: ${failure.message}"
                }
                ready = true
            }
            startPendingImports()
            scope.launch {
                if (!writable) return@launch
                try {
                    val candidates = withContext(Dispatchers.IO) { storage?.reconciliationCandidates().orEmpty() }
                    for (file in candidates) {
                        yield()
                        mutex.withLock {
                            // Imports and saves may have changed ownership during discovery.
                            val referenced = reconciliationReferences + items.map { it.assetId }
                            withContext(Dispatchers.IO) { storage?.removeUnreferenced(file, referenced) }
                        }
                    }
                } catch (failure: Exception) {
                    Log.w("BoardStore", "Asset reconciliation failed; retry on next startup", failure)
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                mutex.withLock { if (dirty) saveEdits() }
            }
        }
    }

    private fun edit(change: () -> Unit) {
        scope.launch { mutex.withLock { if (writable && ready) { change(); dirty = true } } }
    }

    fun transformViewport(focal: CanvasPoint, pan: CanvasPoint, zoom: Float, size: CanvasSize,
        floatingMode: Boolean = false) = edit {
        val transformed = viewportFor(floatingMode).transformedBy(focal, pan, zoom, size)
        if (floatingMode) floating = transformed else fullScreen = transformed
    }

    private fun normalizedStack(content: List<BoardItem>) = content.inStackingOrder()
        .mapIndexed { index, item -> item.copy(zIndex = (index + 1) * 10) }

    fun update(item: BoardItem) = edit {
        items = items.map { if (it.id == item.id) item else it }
    }

    fun save() {
        scope.launch { mutex.withLock { if (dirty) saveEdits() } }
    }

    private fun snapshot(content: List<BoardItem> = items) = BoardSnapshot(content, fullScreen, floating)

    private suspend fun persist(content: List<BoardItem> = items): List<BoardItem> {
        val normalized = if (content.map { it.zIndex }.distinct().size != content.size) {
            normalizedStack(content)
        } else content
        val snapshot = snapshot(normalized)
        withContext(Dispatchers.IO) { storage?.save(snapshot) }
        reconciliationReferences = normalized.map { it.assetId }.toSet()
        dirty = false
        return normalized
    }

    private suspend fun saveEdits() {
        if (!writable || !ready) return
        try { items = persist() }
        catch (failure: Exception) { message = "Board could not be saved: ${failure.message}" }
    }

    fun delete(id: String) {
        scope.launch {
            mutex.withLock {
                if (!writable || !ready) return@withLock
                val item = items.firstOrNull { it.id == id } ?: return@withLock
                try {
                    val remaining = persist(items.filterNot { it.id == id })
                    items = remaining
                    renderer.remove(id)
                    val source = sources.remove(id)
                    if (remaining.none { it.assetId == item.assetId }) {
                        withContext(Dispatchers.IO) {
                            if (storage != null) {
                                storage.removeUnreferenced(storage.asset(item.assetId), reconciliationReferences)
                            } else {
                                source?.file?.delete()
                            }
                        }
                    }
                } catch (failure: Exception) { message = "Image could not be deleted: ${failure.message}" }
            }
        }
    }

    fun refreshImages(density: Float, floatingMode: Boolean = false) {
        renderer.refresh(items, sources, viewportFor(floatingMode), windowSize, density)
    }

    internal fun attachCanvas(owner: Any) {
        canvasOwners.add(owner)
        activeCanvasOwner = owner
    }

    internal fun isActiveCanvas(owner: Any): Boolean = activeCanvasOwner === owner

    internal fun refreshImages(owner: Any, density: Float, floatingMode: Boolean, size: CanvasSize) {
        if (!isActiveCanvas(owner)) return
        resizeWindow(size)
        renderer.refresh(items, sources, viewportFor(floatingMode), size, density)
    }

    internal fun detachCanvas(owner: Any) {
        canvasOwners.remove(owner)
        activeCanvasOwner = canvasOwners.lastOrNull()
        if (canvasOwners.isEmpty()) renderer.clear()
    }

    fun releaseImages() { renderer.clear() }

    fun enqueueImport(resolver: ContentResolver, uris: List<Uri>) {
        if (uris.isEmpty()) return
        pending.addLast(resolver to uris)
        startPendingImports()
    }

    fun resizeWindow(size: CanvasSize) {
        windowSize = size
        startPendingImports()
    }

    private fun startPendingImports() {
        if (!ready || !writable || importing || pending.isEmpty() || windowSize.width <= 0f || windowSize.height <= 0f) return
        importing = true
        scope.launch {
            try {
                while (pending.isNotEmpty()) {
                    val (resolver, uris) = pending.removeFirst()
                    var accepted = 0
                    var rejected = 0
                    for (uri in uris) {
                        val asset = try {
                            mutex.withLock {
                                withContext(Dispatchers.IO) {
                                    if (storage != null) {
                                        val imported = storage.import(resolver, uri)
                                        try {
                                            imported.id to readImageSource(storage.asset(imported.id))
                                        } catch (failure: Exception) {
                                            storage.asset(imported.id).delete()
                                            throw failure
                                        } finally { imported.bitmap.recycle() }
                                    } else {
                                        UUID.randomUUID().toString() to copyImageSource(resolver, uri, null)
                                    }
                                }.also { reconciliationReferences = reconciliationReferences + it.first }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { rejected++; continue }
                        mutex.withLock {
                            try {
                                val (assetId, source) = asset
                                val center = importCenters(accepted + 1, viewport, windowSize).last()
                                val maxDimension = minOf(windowSize.width, windowSize.height) * 0.4f / viewport.zoom
                                val scale = maxDimension / maxOf(source.width, source.height)
                                val existing = if ((items.maxOfOrNull { it.zIndex } ?: 0) > Int.MAX_VALUE - 10) {
                                    normalizedStack(items)
                                } else items
                                val id = UUID.randomUUID().toString()
                                val item = BoardItem(id, center, source.width * scale, source.height * scale,
                                    zIndex = (existing.maxOfOrNull { it.zIndex } ?: 0) + 10, assetId = assetId)
                                val committed = persist(existing + item)
                                sources[id] = source
                                items = committed
                                accepted++
                            } catch (failure: Exception) {
                                withContext(Dispatchers.IO) { asset.second.file.delete() }
                                rejected++
                            }
                        }
                    }
                    if (rejected > 0) message = "$rejected image(s) could not be imported or saved."
                }
            } finally { importing = false }
        }
    }
}
