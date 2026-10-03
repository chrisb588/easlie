package com.chrisb588.easlie

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasPoint
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.canvas.CanvasViewport
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Real store integration; requires the lead's device run. */
class BoardCollectionStoreTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            Thread.sleep(20)
        }
        fail("Board operation did not complete")
    }

    @Test fun switchingSavesIndependentViewportsAndFailedSaveKeepsCurrentBoard() {
        val root = File(instrumentation.targetContext.cacheDir, "collection-${UUID.randomUUID()}")
        val legacy = File(root, "board")
        lateinit var store: BoardStore
        try {
            main { store = BoardStore(legacy, collectionMigration = true) }
            await { store.collectionReady }
            main { assertNull(store.activeBoardId); store.createBoard("First") }
            await { store.activeBoardId != null }
            lateinit var first: String
            main {
                first = store.activeBoardId!!
                store.viewport = CanvasViewport(CanvasPoint(11f, 12f), 2f)
                store.setViewport(CanvasViewport(CanvasPoint(21f, 22f), 3f), true)
                store.createBoard("Second")
            }
            await { store.activeBoardId != first }
            lateinit var second: String
            main {
                second = store.activeBoardId!!
                assertEquals(CanvasViewport(), store.viewport)
                assertEquals(CanvasViewport(), store.viewportFor(true))
                store.openBoard(first)
            }
            await { store.activeBoardId == first }
            main {
                assertEquals(CanvasViewport(CanvasPoint(11f, 12f), 2f), store.viewport)
                assertEquals(CanvasViewport(CanvasPoint(21f, 22f), 3f), store.viewportFor(true))
                // Force an atomic-save failure before the switch can mutate the index.
                File(root, "boards/$first/board.json.tmp").mkdir()
                store.viewport = CanvasViewport(CanvasPoint(31f, 32f), 4f)
                store.openBoard(second)
            }
            await { store.message?.contains("could not be opened") == true }
            main { assertEquals(first, store.activeBoardId) }
            assertEquals(first, BoardCollectionStorage(root, legacy).readCollection().activeBoardId)
            main { store.releaseImages() }
        } finally { root.deleteRecursively() }
    }
    @Test fun importCompletionAfterSwitchKeepsOriginalDestinationAndIndependentCopies() {
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val provider = Uri.parse("content://com.chrisb588.easlie.test.images")
        val source = Uri.withAppendedPath(provider, "task-first.png")
        resolver.call(provider, "create-task-fixtures", null, null)
        val root = File(context.cacheDir, "collection-${UUID.randomUUID()}")
        lateinit var store: BoardStore
        try {
            main { store = BoardStore(File(root, "board"), collectionMigration = true) }
            await { store.collectionReady }
            main { store.resizeWindow(CanvasSize(600f, 400f)); store.createBoard("First") }
            await { store.activeBoardId != null }
            lateinit var first: String
            main { first = store.activeBoardId!! }
            resolver.call(provider, "hold-reads", null, null)
            main { store.enqueueImport(resolver, listOf(source)) }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (resolver.call(provider, "read-started", null, null)?.getBoolean("started") != true) {
                check(android.os.SystemClock.uptimeMillis() < deadline) { "Import did not begin copying" }
                Thread.sleep(20)
            }
            main { store.createBoard("Second") }
            await { store.activeBoardId != first }
            lateinit var second: String
            main { second = store.activeBoardId!!; assertTrue(store.items.isEmpty()) }
            resolver.call(provider, "release-reads", null, null)
            await { !store.importing }
            main { assertEquals(second, store.activeBoardId); assertTrue(store.items.isEmpty()); store.enqueueImport(resolver, listOf(source)) }
            await { !store.importing && store.items.size == 1 }
            val firstStorage = BoardStorage(File(root, "boards/$first"))
            val secondStorage = BoardStorage(File(root, "boards/$second"))
            val firstItem = firstStorage.load().snapshot.items.single()
            val secondItem = secondStorage.load().snapshot.items.single()
            assertNotEquals(firstItem.id, secondItem.id)
            assertNotEquals(firstItem.assetId, secondItem.assetId)
            assertNotEquals(firstStorage.asset(firstItem.assetId), secondStorage.asset(secondItem.assetId))
            assertArrayEquals(firstStorage.asset(firstItem.assetId).readBytes(), secondStorage.asset(secondItem.assetId).readBytes())
            main { store.openBoard(first) }
            await { store.activeBoardId == first && store.items.size == 1 }
            main { assertEquals(firstItem, store.items.single()); store.releaseImages() }
        } finally {
            resolver.call(provider, "release-reads", null, null)
            resolver.delete(source, null, null)
            root.deleteRecursively()
        }
    }

    @Test fun renamingDuringImportPreservesBoardIdentityContentViewportsAndRecentOrder() {
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val provider = Uri.parse("content://com.chrisb588.easlie.test.images")
        val source = Uri.withAppendedPath(provider, "task-first.png")
        resolver.call(provider, "create-task-fixtures", null, null)
        val root = File(context.cacheDir, "collection-${UUID.randomUUID()}")
        lateinit var store: BoardStore
        try {
            main { store = BoardStore(File(root, "board"), collectionMigration = true) }
            await { store.collectionReady }
            main { store.resizeWindow(CanvasSize(600f, 400f)); store.createBoard("Alpha") }
            await { store.activeBoardId != null }
            lateinit var alphaId: String
            main {
                alphaId = store.activeBoardId!!
                store.createBoard("Beta")
            }
            await { store.activeBoardId != alphaId }
            main { store.openBoard(alphaId) }
            await { store.activeBoardId == alphaId }
            val full = CanvasViewport(CanvasPoint(11f, 12f), 2f)
            val floating = CanvasViewport(CanvasPoint(21f, 22f), 3f)
            main {
                store.setViewport(full, floatingMode = false)
                store.setViewport(floating, floatingMode = true)
            }
            await { store.viewportFor(false) == full && store.viewportFor(true) == floating }
            main { store.enqueueImport(resolver, listOf(source)) }
            await { !store.importing && store.items.size == 1 }
            lateinit var originalItems: List<com.chrisb588.easlie.canvas.BoardItem>
            main { originalItems = store.items }

            resolver.call(provider, "hold-reads", null, null)
            main { store.enqueueImport(resolver, listOf(source)) }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (resolver.call(provider, "read-started", null, null)?.getBoolean("started") != true) {
                check(android.os.SystemClock.uptimeMillis() < deadline) { "Import did not begin copying" }
                Thread.sleep(20)
            }

            lateinit var orderBefore: List<String>
            main { orderBefore = store.boards.map { it.id } }
            main { store.renameBoard(alphaId, "Beta") }
            await { store.boards.firstOrNull { it.id == alphaId }?.name == "Beta (2)" }
            main {
                assertEquals(alphaId, store.activeBoardId)
                assertEquals(orderBefore, store.boards.map { it.id })
                assertEquals("Board renamed to Beta (2).", store.message)
                assertEquals(full, store.viewportFor(false))
                assertEquals(floating, store.viewportFor(true))
            }

            resolver.call(provider, "release-reads", null, null)
            await { !store.importing && store.items.size == 2 }
            val collection = BoardCollectionStorage(root, File(root, "board")).readCollection()
            assertEquals(alphaId, collection.activeBoardId)
            assertEquals("Beta (2)", collection.boards.first { it.id == alphaId }.name)
            val snapshot = BoardStorage(File(root, "boards/$alphaId")).load().snapshot
            lateinit var importedItems: List<com.chrisb588.easlie.canvas.BoardItem>
            main { importedItems = store.items }
            assertEquals(originalItems, importedItems.take(1))
            assertEquals(importedItems, snapshot.items)
            assertEquals(full, snapshot.fullScreen)
            assertEquals(floating, snapshot.floating)
            main { store.releaseImages() }
        } finally {
            resolver.call(provider, "release-reads", null, null)
            resolver.delete(source, null, null)
            root.deleteRecursively()
        }
    }

}
