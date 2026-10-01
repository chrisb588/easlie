package com.chrisb588.easlie.images

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ImageRendererTest {
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
