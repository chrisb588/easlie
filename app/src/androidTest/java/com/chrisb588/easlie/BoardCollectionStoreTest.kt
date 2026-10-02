package com.chrisb588.easlie

import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasPoint
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
}
