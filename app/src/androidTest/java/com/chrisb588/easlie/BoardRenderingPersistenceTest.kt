package com.chrisb588.easlie

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BoardRenderingPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var done = false
            onMain { done = condition() }
            if (done) return
            Thread.sleep(20)
        }
        fail("Rendering/persistence operation did not complete")
    }

    @Test fun restoredAssetsUpgradeBeyondOldPreviewAndDownsizeWithoutGoingBlank() {
        val directory = File(instrumentation.targetContext.cacheDir, "render-restore-${UUID.randomUUID()}")
        val storage = BoardStorage(directory)
        val asset = storage.asset("fixture").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888).let { bitmap ->
            asset.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val item = BoardItem("image", CanvasPoint(0f, 0f), 1024f, 512f, zIndex = 10, assetId = "fixture")
        val floating = CanvasViewport(CanvasPoint(10f, 20f), 2f)
        storage.save(BoardSnapshot(listOf(item), floating = floating))
        lateinit var store: BoardStore
        try {
            onMain { store = BoardStore(directory); store.resizeWindow(CanvasSize(1200f, 800f)) }
            await { store.items.size == 1 }
            onMain {
                assertTrue(store.images.isEmpty())
                assertEquals(floating, store.viewportFor(true))
                store.refreshImages(1f)
            }
            await { store.images[item.id]?.width == 1024 }
            onMain { store.viewport = CanvasViewport(zoom = 2f); store.refreshImages(1f) }
            await { store.images[item.id]?.width == 2048 }
            onMain {
                val old = store.images.getValue(item.id)
                store.viewport = CanvasViewport(zoom = 0.125f)
                store.refreshImages(1f)
                assertSame(old, store.images[item.id])
            }
            await { store.images[item.id]?.width == 128 }
            onMain {
                store.viewport = CanvasViewport(center = CanvasPoint(10000f, 10000f))
                store.refreshImages(1f)
                assertTrue(store.images.isEmpty())
                assertEquals(floating, store.viewportFor(true))
                store.delete(item.id)
            }
            await { store.items.isEmpty() }
            await { !asset.exists() }
            assertTrue(storage.load().snapshot.items.isEmpty())
        } finally { onMain { store.releaseImages() }; directory.deleteRecursively() }
    }

    @Test fun outgoingHostCannotClearOrResizeTheActiveFloatingRenderer() {
        val fixture = Uri.parse("content://com.chrisb588.easlie.test.images/clipping.png")
        instrumentation.targetContext.contentResolver.call(fixture, "create-clipping-fixture", null, null)
        lateinit var store: BoardStore
        val outgoing = Any()
        val incoming = Any()
        val fullSize = CanvasSize(1200f, 800f)
        val floatingSize = CanvasSize(600f, 400f)
        try {
            onMain {
                store = BoardStore()
                store.attachCanvas(outgoing)
                store.refreshImages(outgoing, 1f, false, fullSize)
                store.enqueueImport(instrumentation.targetContext.contentResolver, listOf(fixture))
            }
            await { store.items.size == 1 && !store.importing }
            onMain { store.refreshImages(outgoing, 1f, false, fullSize) }
            await { store.images.isNotEmpty() }
            onMain {
                store.attachCanvas(incoming)
                store.refreshImages(incoming, 1f, true, floatingSize)
                val image = store.images.values.single()
                store.refreshImages(outgoing, 1f, false, fullSize)
                assertEquals(floatingSize, store.windowSize)
                store.detachCanvas(outgoing)
                assertSame(image, store.images.values.single())
                assertTrue(store.isActiveCanvas(incoming))
                store.detachCanvas(incoming)
                assertTrue(store.images.isEmpty())
            }
        } finally { onMain { store.items.toList().forEach { store.delete(it.id) }; store.releaseImages() } }
    }

    @Test fun failedImportAndDeletionKeepTheRenderedItemAndOwnedAsset() {
        val directory = File(instrumentation.targetContext.cacheDir, "render-commit-${UUID.randomUUID()}")
        val resolver = instrumentation.targetContext.contentResolver
        val fixture = Uri.parse("content://com.chrisb588.easlie.test.images/clipping.png")
        resolver.call(fixture, "create-clipping-fixture", null, null)
        lateinit var store: BoardStore
        try {
            onMain {
                store = BoardStore(directory)
                store.resizeWindow(CanvasSize(600f, 400f))
                store.enqueueImport(resolver, listOf(fixture))
            }
            await { store.items.size == 1 && !store.importing }
            onMain { store.refreshImages(1f) }
            await { store.images.isNotEmpty() }
            val item = BoardStorage(directory).load().snapshot.items.single()
            val original = File(directory, "board.json").readBytes()
            val assets = File(directory, "assets").list()!!.toSet()
            val rendered = store.images.getValue(item.id)
            File(directory, "board.json.tmp").mkdir()
            onMain { store.enqueueImport(resolver, listOf(fixture)) }
            await { !store.importing && store.message?.contains("could not be imported or saved") == true }
            assertArrayEquals(original, File(directory, "board.json").readBytes())
            assertEquals(assets, File(directory, "assets").list()!!.toSet())
            File(directory, "board.json.tmp").mkdir()
            onMain { store.delete(item.id) }
            await { store.message?.startsWith("Image could not be deleted") == true }
            onMain { assertEquals(listOf(item), store.items); assertSame(rendered, store.images[item.id]) }
            assertTrue(BoardStorage(directory).asset(item.assetId).isFile)
            onMain { store.delete(item.id) }
            await { store.items.isEmpty() }
            await { !BoardStorage(directory).asset(item.assetId).exists() }
        } finally { onMain { store.releaseImages() }; directory.deleteRecursively() }
    }
}
