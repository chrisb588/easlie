package com.chrisb588.easlie

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.content.ContentResolver
import android.content.pm.ApplicationInfo
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
        BoardStore(File(filesDir, "board"), memoryClass.toLong() * 1024 * 1024 / 8,
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, collectionMigration = true)
    }
}

/** The active board and all destination-bound edits share one serialization mutex. */
class BoardStore internal constructor(directory: File? = null, cacheBudget: Long = 16L * 1024 * 1024,
    profileImages: Boolean = false, collectionMigration: Boolean = false) {
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
    var migrationFailed by mutableStateOf(false)
        private set
    val canEdit: Boolean get() = ready && writable && !migrationFailed && (collectionStorage == null || activeBoardId != null)
    internal var boards by mutableStateOf<List<StoredBoard>>(emptyList())
        private set
    var activeBoardId by mutableStateOf<String?>(null)
        private set
    val collectionReady: Boolean get() = ready && writable && !migrationFailed

    private val collectionStorage = if (collectionMigration && directory != null)
        BoardCollectionStorage(directory.parentFile ?: directory, directory) else null
    private var storage: BoardStorage? = if (collectionStorage == null) directory?.let { BoardStorage(it) } else null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = ImageRenderer(scope, cacheBudget, profileImages)
    private val sources = mutableMapOf<String, ImageSource>()
    private val canvasOwners = mutableListOf<Any>()
    private var activeCanvasOwner by mutableStateOf<Any?>(null)
    private val mutex = Mutex()
    private data class PendingImport(
        val resolver: ContentResolver,
        val uris: List<Uri>,
        val destination: String?,
        val viewport: CanvasViewport?,
        val size: CanvasSize,
    )
    private val pending = ArrayDeque<PendingImport>()
    private var writable = true
    private var dirty = false
    private var ready = storage == null && collectionStorage == null
    private var reconciliationReferences = emptySet<String>()

    init {
        scope.launch {
            mutex.withLock {
                restoreStorage()
            }
            startPendingImports()
            reconcileStorage()
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                mutex.withLock { if (dirty) saveEdits() }
            }
        }
    }

    private suspend fun restoreStorage() {
        try {
            storage = withContext(Dispatchers.IO) {
                if (collectionStorage != null) {
                    val directory = collectionStorage.initialize()
                    val collection = collectionStorage.readCollection()
                    boards = collection.boards
                    activeBoardId = collection.activeBoardId?.takeIf { id -> directory != null && collection.boards.any { it.id == id } }
                    directory?.let(::BoardStorage)
                } else storage
            }
            items = emptyList()
            sources.clear()
            renderer.clear()
            fullScreen = CanvasViewport()
            floating = CanvasViewport()
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
                reconciliationReferences = restored.referencedAssets
            }
            if (collectionStorage != null && activeBoardId == null && boards.isNotEmpty()) {
                message = "The previous board is unavailable. Open or create a board."
            }
            writable = true
            migrationFailed = false
            ready = true
        } catch (failure: Exception) {
            writable = false
            if (collectionStorage != null) {
                migrationFailed = true
                ready = false
                message = if (collectionStorage.hasCollection) {
                    "Board collection could not be opened. Retry to continue: ${failure.message}"
                } else {
                    "Board migration failed. Your original board is safe. Retry to continue: ${failure.message}"
                }
            } else {
                message = "Board could not be loaded. Storage is read-only: ${failure.message}"
                ready = true
            }
        }
    }

    fun createBoard(name: String) = changeBoard { it.createBoard(name) }

    fun openBoard(id: String) = changeBoard { it.openBoard(id) }

    fun renameBoard(id: String, name: String) {
        val collection = collectionStorage ?: return
        scope.launch {
            mutex.withLock {
                if (!collectionReady) return@withLock
                try {
                    val updated = withContext(Dispatchers.IO) { collection.renameBoard(id, name) }
                    boards = updated.boards
                    val resolvedName = updated.boards.first { it.id == id }.name
                    message = "Board renamed to $resolvedName."
                } catch (failure: Exception) {
                    message = "Board could not be renamed: ${failure.message}"
                }
            }
        }
    }

    private fun changeBoard(change: (BoardCollectionStorage) -> BoardCollection) {
        val collection = collectionStorage ?: return
        scope.launch {
            mutex.withLock {
                if (!collectionReady) return@withLock
                try {
                    // A failed save throws before either the collection or active canvas changes.
                    if (dirty) items = persist()
                    withContext(Dispatchers.IO) { change(collection) }
                    ready = false
                    restoreStorage()
                } catch (failure: Exception) {
                    message = "Board could not be opened or created: ${failure.message}"
                }
            }
            startPendingImports()
        }
    }

    fun retryMigration() {
        if (collectionStorage == null || !migrationFailed) return
        scope.launch {
            mutex.withLock {
                ready = false
                writable = true
                message = null
                restoreStorage()
            }
            if (canEdit) {
                startPendingImports()
                reconcileStorage()
            }
        }
    }

    private suspend fun reconcileStorage() {
        if (!writable || !ready) return
        try {
            val candidates = withContext(Dispatchers.IO) { storage?.reconciliationCandidates().orEmpty() }
            for (file in candidates) {
                yield()
                mutex.withLock {
                    val referenced = reconciliationReferences + items.map { it.assetId }
                    withContext(Dispatchers.IO) { storage?.removeUnreferenced(file, referenced) }
                }
            }
        } catch (failure: Exception) {
            Log.w("BoardStore", "Asset reconciliation failed; retry on next startup", failure)
        }
    }

    private fun edit(change: () -> Unit) {
        val destination = activeBoardId
        scope.launch { mutex.withLock { if (canEdit && destination == activeBoardId) { change(); dirty = true } } }
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
                        val currentStorage = storage
                        withContext(Dispatchers.IO) {
                            if (currentStorage != null) {
                                currentStorage.removeUnreferenced(currentStorage.asset(item.assetId), reconciliationReferences)
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

    internal fun logImageProfile() { renderer.logProfile() }

    fun enqueueImport(resolver: ContentResolver, uris: List<Uri>) {
        if (uris.isEmpty()) return
        pending.addLast(PendingImport(resolver, uris, activeBoardId, if (canEdit) viewport else null, windowSize))
        startPendingImports()
    }

    fun resizeWindow(size: CanvasSize) {
        windowSize = size
        startPendingImports()
    }

    private fun startPendingImports() {
        if (!canEdit || importing || pending.isEmpty() || windowSize.width <= 0f || windowSize.height <= 0f) return
        importing = true
        scope.launch {
            try {
                while (pending.isNotEmpty()) {
                    val request = pending.removeFirst()
                    val resolver = request.resolver
                    val uris = request.uris
                    // Requests received during startup or management acquire a destination before copying.
                    val destination = request.destination ?: activeBoardId
                    val destinationStorage = if (collectionStorage != null && destination != null) {
                        BoardStorage(collectionStorage.directoryFor(destination))
                    } else storage
                    val importViewport = request.viewport ?: viewport
                    val importSize = request.size.takeIf { it.width > 0f && it.height > 0f } ?: windowSize
                    var accepted = 0
                    var rejected = 0
                    for (uri in uris) {
                        val asset = try {
                            run {
                                val currentStorage = destinationStorage
                                withContext(Dispatchers.IO) {
                                    if (currentStorage != null) {
                                        val imported = currentStorage.import(resolver, uri)
                                        try {
                                            imported.id to readImageSource(currentStorage.asset(imported.id))
                                        } catch (failure: Exception) {
                                            currentStorage.asset(imported.id).delete()
                                            throw failure
                                        } finally { imported.bitmap.recycle() }
                                    } else {
                                        UUID.randomUUID().toString() to copyImageSource(resolver, uri, null)
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { rejected++; continue }
                        mutex.withLock {
                            try {
                                val (assetId, source) = asset
                                val isActiveDestination = destination == activeBoardId
                                val targetSnapshot = if (isActiveDestination) snapshot() else withContext(Dispatchers.IO) {
                                    require(collectionStorage?.readCollection()?.boards?.any { it.id == destination } == true) { "Destination board is unavailable" }
                                    destinationStorage!!.load().snapshot
                                }
                                val targetItems = targetSnapshot.items
                                val center = importCenters(accepted + 1, importViewport, importSize).last()
                                val maxDimension = minOf(importSize.width, importSize.height) * 0.4f / importViewport.zoom
                                val scale = maxDimension / maxOf(source.width, source.height)
                                val existing = if ((targetItems.maxOfOrNull { it.zIndex } ?: 0) > Int.MAX_VALUE - 10) {
                                    normalizedStack(targetItems)
                                } else targetItems
                                val id = UUID.randomUUID().toString()
                                val item = BoardItem(id, center, source.width * scale, source.height * scale,
                                    zIndex = (existing.maxOfOrNull { it.zIndex } ?: 0) + 10, assetId = assetId)
                                if (isActiveDestination) {
                                    val committed = persist(existing + item)
                                    sources[id] = source
                                    items = committed
                                } else {
                                    withContext(Dispatchers.IO) { destinationStorage!!.save(targetSnapshot.copy(items = existing + item)) }
                                }
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
