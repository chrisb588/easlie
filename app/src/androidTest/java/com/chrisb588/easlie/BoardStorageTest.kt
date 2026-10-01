package com.chrisb588.easlie

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.*
import com.chrisb588.easlie.images.loadImage
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BoardStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context

    private fun withStorage(test: (BoardStorage, File) -> Unit) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "storage-test-${java.util.UUID.randomUUID()}")
        try { test(BoardStorage(directory), directory) }
        finally { directory.deleteRecursively() }
    }

    @Test fun manifestRoundTripPreservesEveryPropertyAndBothViewports() = withStorage { storage, _ ->
        storage.asset("asset-1").apply { parentFile!!.mkdirs(); writeText("owned bytes") }
        val snapshot = BoardSnapshot(listOf(BoardItem("item-1", CanvasPoint(-2f, 7f), 50f, 30f, 45f, 20, "asset-1")),
            CanvasViewport(CanvasPoint(17f, -12f), 2f), CanvasViewport(CanvasPoint(-8f, 5f), .5f))
        storage.save(snapshot)
        assertEquals(snapshot, storage.load().snapshot)
    }

    @Test fun failedReplacementLeavesPreviousManifestAndRemovesTemporaryFile() = withStorage { storage, directory ->
        storage.save(BoardSnapshot())
        val original = File(directory, "board.json").readBytes()
        // A directory at the temporary path deterministically prevents opening the replacement file.
        File(directory, "board.json.tmp").mkdir()
        try { storage.save(BoardSnapshot(fullScreen = CanvasViewport(zoom = 2f))); fail("Expected failure") }
        catch (_: java.io.IOException) { }
        assertArrayEquals(original, File(directory, "board.json").readBytes())
        assertFalse(File(directory, "board.json.tmp").exists())
    }

    @Test fun concurrentReaderOnlySeesCompleteManifestsDuringReplacement() = withStorage { storage, directory ->
        val first = BoardSnapshot(fullScreen = CanvasViewport(zoom = 1f))
        val second = BoardSnapshot(fullScreen = CanvasViewport(zoom = 2f))
        storage.save(first)
        val firstBytes = File(directory, "board.json").readText()
        storage.save(second)
        val secondBytes = File(directory, "board.json").readText()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val started = java.util.concurrent.CountDownLatch(1)
        val reader = Thread {
            try {
                do {
                    val content = File(directory, "board.json").readText()
                    assertTrue(content == firstBytes || content == secondBytes)
                    reads.incrementAndGet()
                    started.countDown()
                } while (!finished.get())
            } catch (error: Throwable) { failure.set(error); started.countDown() }
        }
        reader.start()
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            repeat(100) { storage.save(if (it % 2 == 0) first else second) }
        } finally {
            finished.set(true)
            reader.join(5000)
        }
        assertFalse(reader.isAlive)
        failure.get()?.let { throw it }
        assertTrue(reads.get() > 1)
        assertFalse(File(directory, "board.json.tmp").exists())
    }

    @Test fun ownedImageSurvivesSourceRemovalAndRejectedImportCleansTemporaryData() = withStorage { storage, directory ->
        val source = File(context.filesDir, "storage-source.png")
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val asset = storage.import(context.contentResolver, Uri.parse("content://com.chrisb588.easlie.test.images/${source.name}"))
        asset.bitmap.recycle()
        source.delete()
        val restored = loadImage(storage.asset(asset.id))
        assertEquals(20, restored.width)
        restored.recycle()
        val before = File(directory, "assets").list()!!.toSet()
        val corrupt = File(context.filesDir, "storage-corrupt.png").apply { writeText("invalid") }
        try {
            storage.import(context.contentResolver, Uri.parse("content://com.chrisb588.easlie.test.images/${corrupt.name}"))
            fail("Expected rejection")
        } catch (_: Exception) { }
        finally { corrupt.delete() }
        assertEquals(before, File(directory, "assets").list()!!.toSet())
    }

    @Test fun missingAssetsAreSkippedWithoutChangingOriginalManifest() = withStorage { storage, directory ->
        storage.save(BoardSnapshot(listOf(BoardItem("missing", CanvasPoint(0f, 0f), 10f, 10f, zIndex = 10))))
        val before = File(directory, "board.json").readBytes()
        val loaded = storage.load()
        assertEquals(1, loaded.skipped)
        assertTrue(loaded.snapshot.items.isEmpty())
        assertArrayEquals(before, File(directory, "board.json").readBytes())
    }

    @Test fun unsupportedSchemaIsRejectedAndReconciliationDoesNotRun() = withStorage { storage, directory ->
        directory.mkdirs()
        val manifest = File(directory, "board.json").apply { writeText("{\"schemaVersion\":99}") }
        val before = manifest.readBytes()
        try { storage.load(); fail("Expected unsupported schema") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, manifest.readBytes())
    }
    @Test fun reportsMissingAndMalformedItemsAndProtectsTheirAssetsUntilSave() = withStorage { storage, directory ->
        storage.asset("valid-asset").apply { parentFile!!.mkdirs(); writeText("owned") }
        storage.asset("malformed-asset").writeText("owned")
        storage.save(BoardSnapshot(listOf(
            BoardItem("valid", CanvasPoint(0f, 0f), 10f, 10f, zIndex = 10, assetId = "valid-asset"),
            BoardItem("missing", CanvasPoint(0f, 0f), 10f, 10f, zIndex = 10, assetId = "missing-asset"),
            BoardItem("malformed", CanvasPoint(0f, 0f), 10f, 10f, zIndex = 10, assetId = "malformed-asset"),
        )))
        val manifest = File(directory, "board.json")
        val json = org.json.JSONObject(manifest.readText())
        json.getJSONArray("items").getJSONObject(2).put("width", -1)
        json.getJSONArray("items").put("not an item")
        manifest.writeText(json.toString())
        val before = manifest.readBytes()
        val restored = storage.load()
        assertEquals(3, restored.skipped)
        assertEquals(1, restored.missing)
        assertEquals(2, restored.malformed)
        assertEquals(listOf("valid"), restored.snapshot.items.map { it.id })
        storage.reconcile(restored.referencedAssets)
        assertTrue(storage.asset("malformed-asset").exists())
        assertArrayEquals(before, manifest.readBytes())
        storage.save(restored.snapshot)
        storage.reconcile(restored.snapshot.items.map { it.assetId }.toSet())
        assertFalse(storage.asset("malformed-asset").exists())
    }

    @Test fun cleanupRemovesStaleFilesPreservesReferencesAndRetriesFailedDeletion() = withStorage { storage, directory ->
        storage.asset("kept").apply { parentFile!!.mkdirs(); writeText("owned") }
        storage.asset("orphan").writeText("unused")
        File(directory, "assets/stale.tmp").writeText("partial")
        File(directory, "board.json.tmp").writeText("partial")
        // Nonempty directory makes deletion fail deterministically without permission assumptions.
        val failed = storage.asset("retry").apply { mkdir(); File(this, "child").writeText("blocked") }
        storage.reconcile(setOf("kept"))
        assertTrue(storage.asset("kept").isFile)
        assertFalse(storage.asset("orphan").exists())
        assertFalse(File(directory, "assets/stale.tmp").exists())
        assertFalse(File(directory, "board.json.tmp").exists())
        assertTrue(failed.exists())
        File(failed, "child").delete()
        storage.reconcile(setOf("kept"))
        assertFalse(failed.exists())
    }

    @Test fun discoveryRechecksReferencesBeforeDeletingNewlyCommittedAsset() = withStorage { storage, _ ->
        val imported = storage.asset("new-asset").apply { parentFile!!.mkdirs(); writeText("owned") }
        val candidates = storage.reconciliationCandidates()
        assertTrue(imported in candidates)
        storage.save(BoardSnapshot(listOf(BoardItem("new", CanvasPoint(0f, 0f), 10f, 10f, zIndex = 10, assetId = "new-asset"))))
        candidates.forEach { storage.removeUnreferenced(it, setOf("new-asset")) }
        assertTrue(imported.isFile)
    }

}
