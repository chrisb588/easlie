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
}
