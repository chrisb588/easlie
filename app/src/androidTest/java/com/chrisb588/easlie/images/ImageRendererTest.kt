package com.chrisb588.easlie.images

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ImageRendererTest {
    @Test fun clearingSeveralQueuedDecodesDoesNotModifyTheCancellationIteration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("queued-clear-test-", ".png", context.cacheDir)
        Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888).let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val source = ImageSource(file, 1024, 512, 0, false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val renderer = ImageRenderer(scope, 16L * 1024 * 1024)
        val items = (1..30).map { BoardItem("image-$it", CanvasPoint(0f, 0f), 128f, 64f, zIndex = it) }
        try {
            withContext(Dispatchers.Main) {
                renderer.refresh(items, items.associate { it.id to source }, CanvasViewport(), CanvasSize(1200f, 800f), 1f)
                renderer.clear()
                assertTrue(renderer.images.isEmpty())
            }
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            withContext(Dispatchers.Main) { assertTrue(renderer.images.isEmpty()) }
        } finally {
            withContext(Dispatchers.Main) { scope.cancel() }
            scope.coroutineContext[Job]!!.join()
            file.delete()
        }
    }

    @Test fun sharperDecodeRetainsPreviewAndLeavingViewportReleasesImages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("render-test-", ".png", context.cacheDir)
        Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888).let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val source = ImageSource(file, 2048, 1024, 0, false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val renderer = ImageRenderer(scope, 16L * 1024 * 1024)
        val item = BoardItem("image", CanvasPoint(0f, 0f), 128f, 64f, zIndex = 0)
        val size = CanvasSize(1200f, 800f)
        suspend fun awaitWidth(width: Int) = withTimeout(5000) {
            while (withContext(Dispatchers.Main) { renderer.images[item.id]?.width } != width) delay(10)
        }
        try {
            withContext(Dispatchers.Main) {
                renderer.refresh(listOf(item), mapOf(item.id to source), CanvasViewport(), size, 1f)
            }
            awaitWidth(128)
            withContext(Dispatchers.Main) {
                renderer.refresh(listOf(item), mapOf(item.id to source), CanvasViewport(zoom = 8f), size, 1f)
                assertEquals(128, renderer.images.getValue(item.id).width)
            }
            awaitWidth(1024)
            withContext(Dispatchers.Main) {
                renderer.refresh(listOf(item), mapOf(item.id to source),
                    CanvasViewport(center = CanvasPoint(10000f, 10000f)), size, 1f)
                assertTrue(renderer.images.isEmpty())
            }
        } finally {
            withContext(Dispatchers.Main) { renderer.clear(); scope.cancel() }
            file.delete()
        }
    }

    @Test fun downsizingByZoomOrResizeKeepsTheCurrentImageUntilReplacementIsReady() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("downsize-test-", ".png", context.cacheDir)
        Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888).let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val source = ImageSource(file, 2048, 1024, 0, false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val renderer = ImageRenderer(scope, 16L * 1024 * 1024)
        val item = BoardItem("image", CanvasPoint(0f, 0f), 1024f, 512f, zIndex = 0)
        val size = CanvasSize(1200f, 800f)
        suspend fun awaitWidth(width: Int) = withTimeout(5000) {
            while (withContext(Dispatchers.Main) { renderer.images[item.id]?.width } != width) delay(10)
        }
        try {
            for (resizeItem in listOf(false, true)) {
                withContext(Dispatchers.Main) {
                    renderer.refresh(listOf(item), mapOf(item.id to source), CanvasViewport(), size, 1f)
                }
                awaitWidth(1024)
                withContext(Dispatchers.Main) {
                    val old = renderer.images.getValue(item.id)
                    val smaller = if (resizeItem) item.copy(width = 128f, height = 64f) else item
                    val viewport = if (resizeItem) CanvasViewport() else CanvasViewport(zoom = 0.125f)
                    renderer.refresh(listOf(smaller), mapOf(item.id to source), viewport, size, 1f)
                    assertSame("A downsize must not publish a blank image", old, renderer.images[item.id])
                }
                awaitWidth(128)
            }
            // A failed replacement must keep the last usable image, too.
            file.delete()
            withContext(Dispatchers.Main) {
                val old = renderer.images.getValue(item.id)
                renderer.refresh(listOf(item.copy(width = 16f, height = 8f)),
                    mapOf(item.id to source), CanvasViewport(), size, 1f)
                assertSame(old, renderer.images[item.id])
            }
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            withContext(Dispatchers.Main) { assertEquals(128, renderer.images.getValue(item.id).width) }
        } finally {
            withContext(Dispatchers.Main) { renderer.clear(); scope.cancel() }
            file.delete()
        }
    }

    @Test fun admittingNewImageDoesNotEvictAnUnchangedVisibleImageDuringDownsize() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = (0..3).map { File.createTempFile("crowded-render-", ".png", context.cacheDir) }
        val dimensions = listOf(384 to 320, 384 to 320, 384 to 320, 512 to 512)
        files.zip(dimensions).forEach { (file, dimensions) ->
            Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888).let { bitmap ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val sources = files.zip(dimensions).mapIndexed { index, (file, dimensions) ->
            "$index" to ImageSource(file, dimensions.first, dimensions.second, 0, false)
        }.toMap()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val renderer = ImageRenderer(scope, 2400L * 1024)
        val a = BoardItem("0", CanvasPoint(0f, 0f), 384f, 320f, zIndex = 0)
        val c = a.copy(id = "1", zIndex = 1)
        val d = a.copy(id = "2", zIndex = 2)
        val b = a.copy(id = "3", width = 512f, height = 512f, zIndex = 2)
        val size = CanvasSize(1200f, 800f)
        suspend fun finishDecodes() = withTimeout(5000) {
            do {
                val children = scope.coroutineContext[Job]!!.children.toList()
                children.joinAll()
            } while (scope.coroutineContext[Job]!!.children.any())
        }
        try {
            repeat(20) {
                withContext(Dispatchers.Main) { renderer.clear() }
                withContext(Dispatchers.Main) {
                    renderer.refresh(listOf(a, b), sources, CanvasViewport(), size, 1f)
                }
                finishDecodes()
                withContext(Dispatchers.Main) {
                    renderer.refresh(listOf(a, c, d, b), sources, CanvasViewport(zoom = 8f), size, 1f)
                }
                finishDecodes()
                withContext(Dispatchers.Main) {
                    assertEquals("A settled viewport must retain every visible image", setOf("0", "1", "2", "3"), renderer.images.keys)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { renderer.clear(); scope.cancel() }
            files.forEach { it.delete() }
        }
    }

    @Test fun subsamplingPreservesExifOrientationWithoutKeepingSourcePixels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("oriented-test-", ".png", context.cacheDir)
        Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            val source = ImageSource(file, 400, 800, 90, false)
            val bitmap = withContext(Dispatchers.IO) { source.decode(4) }
            assertEquals(100, bitmap.width)
            assertEquals(200, bitmap.height)
            bitmap.recycle()
        } finally { file.delete() }
    }
}
