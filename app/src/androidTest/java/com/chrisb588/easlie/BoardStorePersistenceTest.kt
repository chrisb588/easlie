package com.chrisb588.easlie

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BoardStorePersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.context
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var done = false
            onMain { done = condition() }
            if (done) return
            Thread.sleep(20)
        }
        fail("Persistence operation did not complete")
    }

    @Test fun importsCommitBeforePublicationAndDeletionFailurePreservesItemAndAsset() {
        val directory = File(context.cacheDir, "board-store-${UUID.randomUUID()}")
        val source = File(context.filesDir, "store-import.png")
        val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        lateinit var store: BoardStore
        try {
            onMain {
                store = BoardStore(directory)
                store.resizeWindow(CanvasSize(600f, 400f))
                store.enqueueImport(context.contentResolver,
                    listOf(Uri.parse("content://com.chrisb588.easlie.test.images/${source.name}")))
            }
            await { store.items.size == 1 && !store.importing }
            val persisted = BoardStorage(directory).load().snapshot.items.single()
            assertTrue(BoardStorage(directory).asset(persisted.assetId).isFile)
            val existingManifest = File(directory, "board.json").readBytes()
            val existingAssets = File(directory, "assets").list()!!.toSet()
            File(directory, "board.json.tmp").mkdir()
            onMain {
                store.enqueueImport(context.contentResolver,
                    listOf(Uri.parse("content://com.chrisb588.easlie.test.images/${source.name}")))
            }
            await { !store.importing && store.message?.contains("could not be imported or saved") == true }
            assertArrayEquals(existingManifest, File(directory, "board.json").readBytes())
            assertEquals(existingAssets, File(directory, "assets").list()!!.toSet())
            onMain { assertEquals(listOf(persisted), store.items) }
            source.delete()
            File(directory, "board.json.tmp").mkdir()
            onMain { store.delete(persisted.id) }
            await { store.message?.startsWith("Image could not be deleted") == true }
            onMain { assertEquals(listOf(persisted), store.items) }
            assertEquals(listOf(persisted), BoardStorage(directory).load().snapshot.items)
            assertTrue(BoardStorage(directory).asset(persisted.assetId).isFile)
            onMain { store.message = null; store.delete(persisted.id) }
            await { store.items.isEmpty() }
            assertTrue(BoardStorage(directory).load().snapshot.items.isEmpty())
            await { !BoardStorage(directory).asset(persisted.assetId).exists() }
        } finally { source.delete(); directory.deleteRecursively() }
    }

    @Test fun continuousTransformsSavePeriodicallyAndExplicitBoundarySavesBothViewports() {
        val directory = File(context.cacheDir, "board-store-${UUID.randomUUID()}")
        lateinit var store: BoardStore
        try {
            onMain {
                store = BoardStore(directory)
            }
            // Keep editing over multiple one-second save intervals, without calling save().
            repeat(25) {
                onMain { store.transformViewport(CanvasPoint(100f, 100f), CanvasPoint(10f, 0f), 1f, CanvasSize(200f, 200f)) }
                Thread.sleep(100)
                if (it == 14) {
                    assertTrue(File(directory, "board.json").isFile)
                    assertTrue(BoardStorage(directory).load().snapshot.fullScreen.center.x < 0f)
                }
            }
            await { File(directory, "board.json").isFile && BoardStorage(directory).load().snapshot.fullScreen.center.x == -250f }
            assertEquals(-250f, BoardStorage(directory).load().snapshot.fullScreen.center.x)
            val floating = CanvasViewport(CanvasPoint(50f, 60f), 2f)
            onMain { store.setViewport(floating, true); store.save() }
            await { BoardStorage(directory).load().snapshot.floating == floating }
            assertEquals(-250f, BoardStorage(directory).load().snapshot.fullScreen.center.x)
        } finally { directory.deleteRecursively() }
    }
}
