package com.chrisb588.easlie

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.images.sharedImageUris
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Store/intake integration; execution remains part of the lead's device validation. */
class ImportRoutingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val resolver get() = instrumentation.targetContext.contentResolver
    private val provider = Uri.parse("content://com.chrisb588.easlie.test.images")
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            Thread.sleep(20)
        }
        fail("Import operation did not complete")
    }
    private fun fixture(name: String): Uri = Uri.withAppendedPath(provider, name)
    private fun writeImage(uri: Uri) {
        val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
        try { resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun root() = File(instrumentation.targetContext.cacheDir, "routing-${UUID.randomUUID()}")
    private fun snapshot(root: File, id: String) = BoardStorage(File(root, "boards/$id")).load().snapshot

    @Test fun singleAndMultipleSharesAtStartupUseSavedIdentityBeforeLayoutOrSwitch() {
        val source = fixture("routing-startup.png")
        val secondSource = fixture("routing-startup-second.png")
        writeImage(source)
        writeImage(secondSource)
        try {
            for (multiple in listOf(false, true)) {
                val root = root()
                try {
                    val collection = BoardCollectionStorage(root, File(root, "board"))
                    collection.initialize()
                    val saved = collection.createBoard("Saved").activeBoardId!!
                    val other = collection.createBoard("Other").activeBoardId!!
                    collection.openBoard(saved)
                    val intent = Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).setType("image/png")
                    if (multiple) intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(source, secondSource))
                    else intent.putExtra(Intent.EXTRA_STREAM, source)
                    lateinit var store: BoardStore
                    main {
                        store = BoardStore(File(root, "board"), collectionMigration = true)
                        store.enqueueImport(resolver, sharedImageUris(intent))
                    }
                    await { store.activeBoardId == saved }
                    main { store.openBoard(other) }
                    await { store.activeBoardId == other }
                    main { store.resizeWindow(CanvasSize(600f, 400f)) }
                    await { !store.importing && snapshot(root, saved).items.size == if (multiple) 2 else 1 }
                    main { assertTrue(store.items.isEmpty()); assertEquals(other, store.activeBoardId) }
                    assertTrue(snapshot(root, other).items.isEmpty())
                } finally { root.deleteRecursively() }
            }
        } finally { resolver.delete(source, null, null); resolver.delete(secondSource, null, null) }
    }

    @Test fun noActiveDestinationWaitsForChoiceAndKeepsThatChoiceBeforeLayout() {
        val root = root()
        val source = fixture("routing-choice.png")
        writeImage(source)
        try {
            lateinit var store: BoardStore
            main {
                store = BoardStore(File(root, "board"), collectionMigration = true)
                store.enqueueImport(resolver, listOf(source))
            }
            await { store.awaitingImportDestination }
            main { assertFalse(store.importing); assertTrue(store.items.isEmpty()); store.createBoard("Chosen") }
            await { store.activeBoardId != null }
            lateinit var chosen: String
            main { chosen = store.activeBoardId!!; assertFalse(store.awaitingImportDestination); store.createBoard("Later") }
            await { store.activeBoardId != chosen }
            main { store.resizeWindow(CanvasSize(600f, 400f)) }
            await { !store.importing && snapshot(root, chosen).items.size == 1 }
            main { assertTrue(store.items.isEmpty()) }
        } finally { resolver.delete(source, null, null); root.deleteRecursively() }
    }

    @Test fun mixedPartialImportRetriesOnlyTemporaryFailureAfterRenameAndSwitch() {
        val root = root()
        val good = fixture("routing-good.png")
        val temporary = fixture("routing-temporary.png")
        val invalid = fixture("routing-invalid.png")
        val unsupported = fixture("routing-unsupported.txt")
        writeImage(good)
        resolver.delete(temporary, null, null)
        resolver.openOutputStream(invalid)!!.use { it.write("invalid pixels".toByteArray()) }
        resolver.openOutputStream(unsupported)!!.use { it.write("unsupported".toByteArray()) }
        try {
            lateinit var store: BoardStore
            main { store = BoardStore(File(root, "board"), collectionMigration = true) }
            await { store.collectionReady }
            main { store.resizeWindow(CanvasSize(600f, 400f)); store.createBoard("Destination") }
            await { store.activeBoardId != null }
            lateinit var destination: String
            main { destination = store.activeBoardId!!; store.enqueueImport(resolver, listOf(good, temporary, invalid, unsupported)) }
            await { !store.importing && store.canRetryImport }
            val committed = snapshot(root, destination).items.single()
            main {
                assertTrue(store.message!!.contains("routing-temporary.png"))
                assertTrue(store.message!!.contains("routing-invalid.png"))
                assertTrue(store.message!!.contains("routing-unsupported.txt"))
                store.renameBoard(destination, "Renamed")
                store.createBoard("Other")
            }
            await { store.activeBoardId != destination && store.boards.any { it.id == destination && it.name == "Renamed" } }
            writeImage(temporary)
            // If invalid sources were retried these now-valid bytes would create extra items.
            writeImage(invalid)
            main { store.retryImport() }
            await { !store.importing && !store.canRetryImport && snapshot(root, destination).items.size == 2 }
            assertEquals(committed, snapshot(root, destination).items.first())
            assertEquals(2, File(root, "boards/$destination/assets").listFiles()!!.size)
            main { assertTrue(store.items.isEmpty()) }
            main { store.retryImport() }
            assertEquals(2, snapshot(root, destination).items.size)
        } finally {
            listOf(good, temporary, invalid, unsupported).forEach { resolver.delete(it, null, null) }
            root.deleteRecursively()
        }
    }

    @Test fun temporaryCollectionReadFailureKeepsOnlyUncommittedSourcesAndCleansStaging() {
        val root = root()
        val source = fixture("routing-storage.png")
        writeImage(source)
        val temporaryDirectory = File(System.getProperty("java.io.tmpdir")!!)
        val before = temporaryDirectory.listFiles().orEmpty().filter { it.name.startsWith("reference-") }.toSet()
        try {
            lateinit var store: BoardStore
            main { store = BoardStore(File(root, "board"), collectionMigration = true) }
            await { store.collectionReady }
            main { store.resizeWindow(CanvasSize(600f, 400f)); store.createBoard("Destination") }
            await { store.activeBoardId != null }
            lateinit var destination: String
            main { destination = store.activeBoardId!! }
            resolver.call(provider, "hold-reads", null, null)
            main { store.enqueueImport(resolver, listOf(source)) }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (resolver.call(provider, "read-started", null, null)?.getBoolean("started") != true) {
                check(android.os.SystemClock.uptimeMillis() < deadline) { "Copy did not begin" }
                Thread.sleep(20)
            }
            val index = File(root, "boards.index")
            val backup = File(root, "index-backup")
            assertTrue(index.renameTo(backup))
            assertTrue(index.mkdir())
            resolver.call(provider, "release-reads", null, null)
            await { !store.importing && store.canRetryImport }
            main { assertTrue(store.items.isEmpty()); assertTrue(store.message!!.contains("could not be checked")) }
            assertEquals(before, temporaryDirectory.listFiles().orEmpty().filter { it.name.startsWith("reference-") }.toSet())
            assertTrue(File(root, "boards/$destination/assets").listFiles().orEmpty().isEmpty())
            assertTrue(index.delete())
            assertTrue(backup.renameTo(index))
            main { store.retryImport() }
            await { !store.importing && !store.canRetryImport && store.items.size == 1 }
            assertEquals(1, snapshot(root, destination).items.size)
            assertEquals(1, File(root, "boards/$destination/assets").listFiles()!!.size)
        } finally {
            resolver.call(provider, "release-reads", null, null)
            resolver.delete(source, null, null)
            root.deleteRecursively()
        }
    }

    @Test fun retryOfDeletedDestinationExplainsFailureWithoutRecreatingOrRedirecting() {
        val root = root()
        val temporary = fixture("routing-deleted.png")
        resolver.delete(temporary, null, null)
        try {
            lateinit var store: BoardStore
            main { store = BoardStore(File(root, "board"), collectionMigration = true) }
            await { store.collectionReady }
            main { store.resizeWindow(CanvasSize(600f, 400f)); store.createBoard("Delete") }
            await { store.activeBoardId != null }
            lateinit var destination: String
            main { destination = store.activeBoardId!!; store.enqueueImport(resolver, listOf(temporary)) }
            await { !store.importing && store.canRetryImport }
            main { store.deleteBoard(destination) }
            await { store.activeBoardId == null }
            writeImage(temporary)
            main { store.createBoard("Survivor") }
            await { store.activeBoardId != null }
            main { assertFalse(store.canRetryImport); store.retryImport() }
            await { !store.importing && !store.canRetryImport && store.message?.contains("cannot continue or be retried") == true }
            assertFalse(File(root, "boards/$destination").exists())
            main { assertTrue(store.items.isEmpty()) }
        } finally { resolver.delete(temporary, null, null); root.deleteRecursively() }
    }
}
