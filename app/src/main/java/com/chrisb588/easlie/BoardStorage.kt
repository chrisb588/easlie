package com.chrisb588.easlie

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.system.Os
import android.util.Log
import com.chrisb588.easlie.canvas.*
import com.chrisb588.easlie.images.loadImage
import com.chrisb588.easlie.images.supportedMimeTypes
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class BoardSnapshot(
    val items: List<BoardItem> = emptyList(),
    val fullScreen: CanvasViewport = CanvasViewport(),
    val floating: CanvasViewport = CanvasViewport(),
)

internal data class RestoredBoard(
    val snapshot: BoardSnapshot,
    val skipped: Int,
    val missing: Int = 0,
    val malformed: Int = 0,
    val referencedAssets: Set<String> = snapshot.items.map { it.assetId }.toSet(),
)
internal data class ImportedAsset(val id: String, val bitmap: Bitmap)

/** Called only by the store's serialized I/O operations. */
internal class BoardStorage(private val directory: File) {
    private val assets = File(directory, "assets")
    private val manifest = File(directory, "board.json")

    fun asset(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid asset identifier" }
        return File(assets, id)
    }

    fun import(resolver: ContentResolver, uri: Uri): ImportedAsset {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        val type = resolver.getType(uri)
        require(type == null || type in supportedMimeTypes) { "Unsupported image type" }
        assets.mkdirs()
        val id = UUID.randomUUID().toString()
        val temporary = File(assets, "$id.tmp")
        try {
            resolver.openInputStream(uri)?.use { source ->
                FileOutputStream(temporary).use { target -> source.copyTo(target); target.fd.sync() }
            } ?: error("Unreadable image")
            // Validate and orient the owned copy, so the original URI is never needed again.
            val bitmap = loadImage(temporary)
            try {
                Os.rename(temporary.path, asset(id).path)
            } catch (failure: Exception) {
                bitmap.recycle()
                throw failure
            }
            return ImportedAsset(id, bitmap)
        } finally {
            temporary.delete()
        }
    }

    fun save(snapshot: BoardSnapshot) {
        directory.mkdirs()
        val json = JSONObject().put("schemaVersion", 1)
            .put("viewports", JSONObject().put("fullScreen", viewportJson(snapshot.fullScreen))
                .put("floating", viewportJson(snapshot.floating)))
            .put("items", JSONArray().apply {
                snapshot.items.forEach { item -> put(JSONObject().put("id", item.id).put("assetId", item.assetId)
                    .put("x", item.center.x).put("y", item.center.y).put("width", item.width)
                    .put("height", item.height).put("rotationDegrees", item.rotationDegrees).put("zIndex", item.zIndex)) }
            })
        val temporary = File(directory, "board.json.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // Same-directory rename is atomic; Os.rename reports failures instead of swallowing them.
            Os.rename(temporary.path, manifest.path)
        } finally {
            temporary.delete()
        }
    }

    fun load(): RestoredBoard {
        if (!manifest.exists()) return RestoredBoard(BoardSnapshot(), 0)
        val json = JSONObject(manifest.readText())
        require(json.getInt("schemaVersion") == 1) { "Unsupported board schema; storage is read-only." }
        val viewports = json.getJSONObject("viewports")
        val items = json.getJSONArray("items")
        var missing = 0
        var malformed = 0
        val referencedAssets = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        val restored = buildList {
            for (index in 0 until items.length()) {
                try {
                    val item = items.getJSONObject(index)
                    val id = item.getString("id")
                    val assetId = item.getString("assetId")
                    val file = asset(assetId)
                    referencedAssets.add(assetId)
                    require(id.isNotBlank() && id !in ids) { "Invalid or duplicate item identifier" }
                    if (!file.isFile) {
                        Log.w("BoardStorage", "Cannot restore item $index ($id): missing asset $assetId")
                        missing++
                        continue
                    }
                    val width = item.finiteFloat("width")
                    val height = item.finiteFloat("height")
                    require(width > 0 && height > 0)
                    add(BoardItem(id, CanvasPoint(item.finiteFloat("x"), item.finiteFloat("y")), width, height,
                        item.finiteFloat("rotationDegrees"), item.getInt("zIndex"), assetId))
                    ids.add(id)
                } catch (failure: Exception) {
                    malformed++
                    Log.w("BoardStorage", "Cannot restore item $index", failure)
                }
            }
        }
        return RestoredBoard(BoardSnapshot(restored, readViewport(viewports.getJSONObject("fullScreen")),
            readViewport(viewports.getJSONObject("floating"))), missing + malformed, missing, malformed, referencedAssets)
    }

    /** Discovery runs without the mutation lock; callers recheck references before deletion. */
    fun reconciliationCandidates(): List<File> =
        assets.listFiles()?.toList().orEmpty() + File(directory, "board.json.tmp")

    fun removeUnreferenced(file: File, referenced: Set<String>): Boolean {
        if (file.parentFile == assets && file.name in referenced) return true
        if (!file.exists()) return true
        return file.delete().also { deleted ->
            if (!deleted) Log.w("BoardStorage", "Cleanup deferred until later reconciliation: ${file.name}")
        }
    }

    fun reconcile(referenced: Set<String>) {
        reconciliationCandidates().forEach { removeUnreferenced(it, referenced) }
    }

    private fun viewportJson(viewport: CanvasViewport) = JSONObject().put("centerX", viewport.center.x)
        .put("centerY", viewport.center.y).put("zoom", viewport.zoom)
    private fun readViewport(json: JSONObject) = CanvasViewport(
        CanvasPoint(json.finiteFloat("centerX"), json.finiteFloat("centerY")), json.finiteFloat("zoom"))
    private fun JSONObject.finiteFloat(key: String): Float = getDouble(key).toFloat().also { require(it.isFinite()) }
}
