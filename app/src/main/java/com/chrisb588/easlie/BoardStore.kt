package com.chrisb588.easlie

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.chrisb588.easlie.canvas.BoardItem
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.canvas.CanvasViewport
import com.chrisb588.easlie.canvas.importCenters
import com.chrisb588.easlie.images.loadImage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EaslieApplication : Application() {
    val board = BoardStore()
}

/** One authoritative board for this process. Persistence is a later ticket. */
class BoardStore {
    var items by mutableStateOf<List<BoardItem>>(emptyList())
        private set
    var images by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
        private set
    var viewport by mutableStateOf(CanvasViewport())
    var windowSize by mutableStateOf(CanvasSize(0f, 0f))
    var message by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ArrayDeque<Pair<ContentResolver, List<Uri>>>()

    fun update(item: BoardItem) {
        items = items.map { if (it.id == item.id) item else it }
    }

    fun delete(id: String) {
        items = items.filterNot { it.id == id }
        images = images - id
    }

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
        if (importing || pending.isEmpty() || windowSize.width <= 0f || windowSize.height <= 0f) return
        importing = true
        scope.launch {
            try {
                while (pending.isNotEmpty()) {
                    val (resolver, uris) = pending.removeFirst()
                    var accepted = 0
                    var rejected = 0
                    for (uri in uris) {
                        val bitmap = try {
                            withContext(Dispatchers.IO) { loadImage(resolver, uri) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            rejected++
                            continue
                        }
                        // Read the current viewport after decoding: the user can pan while importing.
                        val center = importCenters(accepted + 1, viewport, windowSize).last()
                        val maxDimension = minOf(windowSize.width, windowSize.height) * 0.4f / viewport.zoom
                        val scale = maxDimension / maxOf(bitmap.width, bitmap.height)
                        val id = UUID.randomUUID().toString()
                        // Normalize before integer overflow while preserving the existing stack order.
                        if ((items.maxOfOrNull { it.zIndex } ?: 0) > Int.MAX_VALUE - 10) {
                            items = items.sortedBy { it.zIndex }.mapIndexed { index, item -> item.copy(zIndex = index * 10) }
                        }
                        images = images + (id to bitmap.asImageBitmap())
                        items = items + BoardItem(id, center, bitmap.width * scale, bitmap.height * scale,
                            zIndex = (items.maxOfOrNull { it.zIndex } ?: 0) + 10)
                        accepted++
                    }
                    if (rejected > 0) message = "$rejected unsupported or unreadable image(s) were skipped."
                }
            } finally {
                importing = false
            }
        }
    }
}
