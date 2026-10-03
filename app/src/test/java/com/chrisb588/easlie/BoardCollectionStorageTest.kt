package com.chrisb588.easlie

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.chrisb588.easlie.canvas.*
import org.junit.Assert.*
import org.junit.Test

class BoardCollectionStorageTest {
    private fun withDirectories(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("easlie-migration-").toFile()
        val legacy = File(root, "board")
        try { test(root, legacy) } finally { root.deleteRecursively() }
    }

    private fun seedLegacy(legacy: File): Pair<ByteArray, ByteArray> {
        File(legacy, "assets").mkdirs()
        val manifest = """{"schemaVersion":1,"viewports":{"fullScreen":{"centerX":3.5,"centerY":-7.0,"zoom":2.0},"floating":{"centerX":9.0,"centerY":11.0,"zoom":0.5}},"items":[{"id":"item-a","assetId":"asset-a","x":12.0,"y":-4.0,"width":160.0,"height":90.0,"rotationDegrees":27.0,"zIndex":40}]}""".toByteArray()
        val asset = byteArrayOf(0, 1, 2, 3, 4, 5)
        File(legacy, "board.json").writeBytes(manifest)
        File(legacy, "assets/asset-a").writeBytes(asset)
        return manifest to asset
    }

    @Test fun populatedLegacyBoardIsCopiedAndRetriedMigrationKeepsOneStableBoard() = withDirectories { root, legacy ->
        val (manifest, asset) = seedLegacy(legacy)
        val first = newStorage(root, legacy)
        val migratedDirectory = first.initialize()!!

        assertEquals("Board 1", first.readCollection().boards.single().name)
        assertEquals("00000000-0000-4000-8000-000000000001", migratedDirectory.name)
        assertArrayEquals(manifest, File(migratedDirectory, "board.json").readBytes())
        assertArrayEquals(asset, File(migratedDirectory, "assets/asset-a").readBytes())
        assertEquals(
            BoardSnapshot(
                listOf(BoardItem("item-a", CanvasPoint(12f, -4f), 160f, 90f, 27f, 40, "asset-a")),
                CanvasViewport(CanvasPoint(3.5f, -7f), 2f),
                CanvasViewport(CanvasPoint(9f, 11f), .5f),
            ),
            BoardStorage(migratedDirectory).load().snapshot,
        )
        assertArrayEquals(manifest, File(legacy, "board.json").readBytes())
        assertArrayEquals(asset, File(legacy, "assets/asset-a").readBytes())
        assertEquals(migratedDirectory, newStorage(root, legacy).initialize())
        assertEquals(1, first.readCollection().boards.size)
    }

    @Test fun failedCopyLeavesOriginalAndRetryCompletesMigration() = withDirectories { root, legacy ->
        val (manifest, asset) = seedLegacy(legacy)
        var fail = true
        val storage = BoardCollectionStorage(root, legacy, copyDirectory = { source, destination ->
            if (fail) {
                destination.mkdirs()
                File(destination, "partial").writeText("partial")
                throw IllegalStateException("injected copy interruption")
            }
            copyTree(source, destination)
        }, validateBoard = ::validateFixture, atomicReplace = ::atomicMove)
        try { storage.initialize(); fail("Expected copy failure") }
        catch (expected: IllegalStateException) { assertEquals("injected copy interruption", expected.message) }

        assertFalse(File(root, "boards.index").exists())
        assertArrayEquals(manifest, File(legacy, "board.json").readBytes())
        assertArrayEquals(asset, File(legacy, "assets/asset-a").readBytes())
        fail = false
        val migrated = storage.initialize()!!
        assertArrayEquals(manifest, File(migrated, "board.json").readBytes())
        assertArrayEquals(asset, File(migrated, "assets/asset-a").readBytes())
        assertFalse(File(migrated, "partial").exists())
    }

    @Test fun interruptionAfterCopyBeforeIndexRetriesWithoutDuplicateBoard() = withDirectories { root, legacy ->
        val (manifest, asset) = seedLegacy(legacy)
        var failIndexWrite = true
        val interrupted = BoardCollectionStorage(root, legacy, validateBoard = ::validateFixture, atomicReplace = { _, _ ->
            if (failIndexWrite) throw IllegalStateException("injected index interruption")
        })
        try { interrupted.initialize(); fail("Expected index failure") }
        catch (expected: IllegalStateException) { assertEquals("injected index interruption", expected.message) }

        val partialBoard = File(root, "boards/00000000-0000-4000-8000-000000000001")
        assertArrayEquals(manifest, File(partialBoard, "board.json").readBytes())
        assertArrayEquals(asset, File(partialBoard, "assets/asset-a").readBytes())
        assertFalse(File(root, "boards.index").exists())
        failIndexWrite = false
        val migrated = newStorage(root, legacy)
        assertEquals(partialBoard, migrated.initialize())
        assertEquals(1, migrated.readCollection().boards.size)
    }

    @Test fun unsupportedFutureCollectionIsNotModifiedOrMigratedOver() = withDirectories { root, legacy ->
        seedLegacy(legacy)
        val index = File(root, "boards.index").apply { writeText("easlie-board-collection\nschemaVersion=99\nfuture=keep\n") }
        val before = index.readBytes()
        try { newStorage(root, legacy).initialize(); fail("Expected unsupported format") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("Unsupported")) }
        assertArrayEquals(before, index.readBytes())
        assertFalse(File(root, "boards/00000000-0000-4000-8000-000000000001").exists())
        assertTrue(File(legacy, "board.json").isFile)
    }

    @Test fun unsupportedLegacyBoardManifestDoesNotPublishCollectionOrTouchOriginal() = withDirectories { root, legacy ->
        val (manifest, asset) = seedLegacy(legacy)
        val unsupported = "{\"schemaVersion\":99}".toByteArray()
        File(legacy, "board.json").writeBytes(unsupported)
        val storage = newStorage(root, legacy)
        try { storage.initialize(); fail("Expected unsupported legacy schema") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("Unsupported")) }
        assertFalse(File(root, "boards.index").exists())
        assertArrayEquals(unsupported, File(legacy, "board.json").readBytes())
        assertArrayEquals(asset, File(legacy, "assets/asset-a").readBytes())
    }

    @Test fun legacyAssetsWithoutManifestDoNotBecomeSuccessfulEmptyBoard() = withDirectories { root, legacy ->
        val asset = File(legacy, "assets/orphan").apply { parentFile!!.mkdirs(); writeText("preserve") }
        try { newStorage(root, legacy).initialize(); fail("Expected incomplete legacy board failure") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("manifest")) }
        assertFalse(File(root, "boards.index").exists())
        assertEquals("preserve", asset.readText())
    }

    @Test fun noLegacyBoardInitializesAnEmptyManagementCollection() = withDirectories { root, legacy ->
        val storage = newStorage(root, legacy)

        assertNull(storage.initialize())
        assertEquals(BoardCollection(null, emptyList()), storage.readCollection())
        assertEquals("activeBoard=", File(root, "boards.index").readLines().first { it.startsWith("activeBoard=") })
    }

    @Test fun createdBoardsAreEmptyUniquelyNamedAndOrderedByMostRecentOpen() = withDirectories { root, legacy ->
        val storage = storageForManagement(root, legacy, ids = ArrayDeque(listOf("board-a", "board-a")))
        storage.writeCollection(BoardCollection(null, emptyList()))

        val first = storage.createBoard("  References  ")
        val second = storage.createBoard("References")
        assertEquals("References", first.boards.first().name)
        assertEquals("References (2)", second.boards.first().name)
        assertEquals("board-a-2", second.activeBoardId)
        assertTrue(File(root, "boards/board-a/board.json").isFile)
        assertTrue(File(root, "boards/board-a-2/board.json").isFile)
        assertTrue(BoardStorage(storage.directoryFor("board-a")).load().snapshot.items.isEmpty())

        val opened = storage.openBoard("board-a")
        assertEquals("board-a", opened.activeBoardId)
        assertEquals(listOf("board-a", "board-a-2"), opened.boards.map { it.id })
        assertEquals(opened, storage.readCollection())
    }

    @Test fun renameResolvesCollisionsPersistsNameAndPreservesIdentityActiveBoardAndOrder() = withDirectories { root, legacy ->
        val storage = storageForManagement(root, legacy, ids = ArrayDeque(listOf("one", "two", "three")))
        storage.writeCollection(BoardCollection(null, emptyList()))
        val one = storage.createBoard("Board 1")
        val two = storage.createBoard("Board 1 (2)")
        val three = storage.createBoard("Third")
        val orderBefore = three.boards.map { it.id }

        val renamed = storage.renameBoard(three.activeBoardId!!, " Board 1 ")
        assertEquals("Board 1 (3)", renamed.boards.first { it.id == three.activeBoardId }.name)
        assertEquals(orderBefore, renamed.boards.map { it.id })
        assertEquals(three.activeBoardId, renamed.activeBoardId)
        assertEquals(renamed, storageForManagement(root, legacy).readCollection())

        val sameName = storage.renameBoard(three.activeBoardId!!, "Board 1 (3)")
        assertEquals("Board 1 (3)", sameName.boards.first { it.id == three.activeBoardId }.name)
        assertEquals(listOf("three", "two", "one"), sameName.boards.map { it.id })
        assertEquals("Board 1 (2)", sameName.boards.first { it.id == two.activeBoardId }.name)
        val restarted = storageForManagement(root, legacy)
        assertEquals(restarted.directoryFor(three.activeBoardId!!), restarted.initialize())
        assertEquals(sameName, restarted.readCollection())
    }

    @Test fun failedRenameLeavesCollectionBytesAndBoardNamesUnchanged() = withDirectories { root, legacy ->
        val working = storageForManagement(root, legacy, ids = ArrayDeque(listOf("one")))
        working.writeCollection(BoardCollection(null, emptyList()))
        val created = working.createBoard("Original")
        val before = File(root, "boards.index").readBytes()
        val failing = BoardCollectionStorage(root, legacy, validateBoard = ::validateFixture,
            atomicReplace = { _, _ -> throw IllegalStateException("injected index failure") })

        try { failing.renameBoard(created.activeBoardId!!, "Replacement"); fail("Expected index write failure") }
        catch (expected: IllegalStateException) { assertEquals("injected index failure", expected.message) }

        assertArrayEquals(before, File(root, "boards.index").readBytes())
        assertEquals("Original", working.readCollection().boards.single().name)
        assertFalse(File(root, "boards.index.tmp").exists())
    }

    @Test fun missingActiveBoardReturnsToManagementWithoutChangingTheCollection() = withDirectories { root, legacy ->
        val storage = storageForManagement(root, legacy)
        val collection = BoardCollection("missing", listOf(StoredBoard("present", "Here")))
        storage.writeCollection(collection)
        File(root, "boards/present").mkdirs()
        writeEmptyFixture(File(root, "boards/present"))
        // Stale storage must not revive an identity that is absent from the collection.
        writeEmptyFixture(storage.directoryFor("missing"))
        val before = File(root, "boards.index").readBytes()

        assertNull(storage.initialize())
        assertArrayEquals(before, File(root, "boards.index").readBytes())
        assertEquals(collection, storage.readCollection())
    }

    @Test fun openingMissingOrInvalidBoardDoesNotChangeActiveBoardOrOrder() = withDirectories { root, legacy ->
        val storage = storageForManagement(root, legacy)
        val collection = BoardCollection("present", listOf(StoredBoard("present", "Here"), StoredBoard("missing", "Gone")))
        storage.writeCollection(collection)
        File(root, "boards/present").mkdirs()
        writeEmptyFixture(File(root, "boards/present"))
        try { storage.openBoard("missing"); fail("Expected missing directory") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("unavailable")) }
        assertEquals(collection, storage.readCollection())
    }

    @Test fun failedSaveBeforeCreateOrOpenLeavesCollectionAndBoardDirectoriesUntouched() = withDirectories { root, legacy ->
        val storage = storageForManagement(root, legacy, ids = ArrayDeque(listOf("new-board")))
        val collection = BoardCollection("present", listOf(StoredBoard("present", "Here"), StoredBoard("next", "Next")))
        storage.writeCollection(collection)
        writeEmptyFixture(storage.directoryFor("present"))
        writeEmptyFixture(storage.directoryFor("next"))
        val indexBefore = File(root, "boards.index").readBytes()

        try { storage.openBoard("next") { error("save failed") }; fail("Expected save failure") }
        catch (expected: IllegalStateException) { assertEquals("save failed", expected.message) }
        try { storage.createBoard("New") { error("save failed") }; fail("Expected save failure") }
        catch (expected: IllegalStateException) { assertEquals("save failed", expected.message) }

        assertArrayEquals(indexBefore, File(root, "boards.index").readBytes())
        assertFalse(storage.directoryFor("new-board").exists())
        assertEquals(collection, storage.readCollection())
    }

    @Test fun boardsRetainIndependentStorageAndCapturedAsyncDestinationAfterSwitch() = withDirectories { root, legacy ->
        val storage = storageForManagement(root, legacy, ids = ArrayDeque(listOf("first", "second")))
        storage.writeCollection(BoardCollection(null, emptyList()))
        val first = storage.createBoard("First")
        val second = storage.createBoard("Second")
        val firstDirectory = storage.directoryFor(first.activeBoardId!!)
        val secondDirectory = storage.directoryFor(second.activeBoardId!!)
        File(firstDirectory, "assets/asset-a").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        File(secondDirectory, "assets/asset-a").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(4, 5, 6)) }
        writeBoardFixture(firstDirectory, 1, 4)
        writeBoardFixture(secondDirectory, 7, 10)

        // An async save keeps its captured board directory after another board opens.
        val capturedDestination = firstDirectory
        storage.openBoard("second")
        writeBoardFixture(capturedDestination, 13, 16)

        val restarted = storageForManagement(root, legacy)
        assertEquals("second", restarted.readCollection().activeBoardId)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(firstDirectory, "assets/asset-a").readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), File(secondDirectory, "assets/asset-a").readBytes())
        assertEquals(CanvasViewport(CanvasPoint(13f, 14f), 15f), BoardStorage(firstDirectory).load().snapshot.fullScreen)
        assertEquals(CanvasViewport(CanvasPoint(16f, 17f), 18f), BoardStorage(firstDirectory).load().snapshot.floating)
        assertEquals(CanvasViewport(CanvasPoint(7f, 8f), 9f), BoardStorage(secondDirectory).load().snapshot.fullScreen)
        assertEquals(CanvasViewport(CanvasPoint(10f, 11f), 12f), BoardStorage(secondDirectory).load().snapshot.floating)
    }

    @Test fun inactiveDeletionKeepsActiveBoardIndependentAssetsAndBothViewports() = withDirectories { root, legacy ->
        val storage = storageForManagement(root, legacy, ArrayDeque(listOf("one", "two")))
        storage.writeCollection(BoardCollection(null, emptyList()))
        storage.createBoard("One")
        storage.createBoard("Two")
        val one = storage.directoryFor("one")
        val two = storage.directoryFor("two")
        writeBoardFixture(two, 7, 10)
        File(one, "assets/shared-source").apply { parentFile!!.mkdirs(); writeText("independent copy") }
        val survivor = File(two, "assets/shared-source").apply { parentFile!!.mkdirs(); writeText("independent copy") }
        val snapshot = BoardStorage(two).load().snapshot
        val result = storage.deleteBoard("one")
        assertNull(result.cleanupError)
        assertEquals("two", result.collection.activeBoardId)
        assertFalse(one.exists())
        assertEquals("independent copy", survivor.readText())
        assertEquals(snapshot, BoardStorage(two).load().snapshot)
        assertEquals(result.collection, storage.readCollection())
    }

    @Test fun activeAndLastDeletionReturnToManagementWithoutRevivingLegacy() = withDirectories { root, legacy ->
        seedLegacy(legacy)
        val storage = storageForManagement(root, legacy, ArrayDeque(listOf("other", "replacement")))
        val migrated = storage.initialize()!!
        storage.createBoard("Other")
        assertNull(storage.deleteBoard("other").collection.activeBoardId)
        assertNull(storage.initialize())
        assertTrue(migrated.exists())
        val empty = storage.deleteBoard(migrated.name)
        assertEquals(BoardCollection(null, emptyList()), empty.collection)
        assertNull(storage.initialize())
        assertTrue(File(legacy, "board.json").isFile)
        assertFalse(migrated.exists())
        assertEquals("Other", storage.createBoard("Other").boards.single().name)
    }

    @Test fun failedDeletionCommitPreservesContentActiveIdentityAndIndex() = withDirectories { root, legacy ->
        val storage = storageForManagement(root, legacy, ArrayDeque(listOf("one")))
        storage.writeCollection(BoardCollection(null, emptyList()))
        storage.createBoard("One")
        val index = File(root, "boards.index").readBytes()
        val manifest = File(storage.directoryFor("one"), "board.json").readBytes()
        val failing = BoardCollectionStorage(root, legacy, atomicReplace = { _, _ -> error("injected deletion failure") })
        try { failing.deleteBoard("one"); fail("Expected deletion failure") }
        catch (expected: IllegalStateException) { assertEquals("injected deletion failure", expected.message) }
        assertArrayEquals(index, File(root, "boards.index").readBytes())
        assertArrayEquals(manifest, File(storage.directoryFor("one"), "board.json").readBytes())
        assertNull(storage.recoverDeletions())
        assertTrue(storage.directoryFor("one").exists())
    }

    @Test fun cleanupFailureRetainsRecoveryMarkerAndRetryNeverTouchesSurvivingBoard() = withDirectories { root, legacy ->
        val storage = storageForManagement(root, legacy, ArrayDeque(listOf("one", "two")))
        storage.writeCollection(BoardCollection(null, emptyList()))
        storage.createBoard("One")
        storage.createBoard("Two")
        val failing = BoardCollectionStorage(root, legacy, atomicReplace = ::atomicMove, removeDirectory = { false })
        val result = failing.deleteBoard("one")
        assertNotNull(result.cleanupError)
        assertEquals("two", result.collection.activeBoardId)
        assertTrue(File(root, "pending-deletions/one").isFile)
        assertTrue(storage.directoryFor("one").exists())
        assertNull(storage.recoverDeletions())
        assertFalse(storage.directoryFor("one").exists())
        assertTrue(storage.directoryFor("two").exists())
        assertFalse(File(root, "pending-deletions/one").exists())
    }

    @Test fun staleCleanupMarkerForOwnedBoardCannotRemoveAssets() = withDirectories { root, legacy ->
        val storage = storageForManagement(root, legacy, ArrayDeque(listOf("one")))
        storage.writeCollection(BoardCollection(null, emptyList()))
        storage.createBoard("One")
        File(root, "pending-deletions/one").apply { parentFile!!.mkdirs(); writeText("one") }
        assertNull(storage.recoverDeletions())
        assertTrue(storage.directoryFor("one").exists())
    }

    private fun newStorage(root: File, legacy: File) = BoardCollectionStorage(
        root, legacy, validateBoard = ::validateFixture, atomicReplace = ::atomicMove,
    )

    private fun storageForManagement(
        root: File,
        legacy: File,
        ids: ArrayDeque<String> = ArrayDeque(),
    ) = BoardCollectionStorage(
        root,
        legacy,
        validateBoard = ::validateFixture,
        atomicReplace = ::atomicMove,
        createEmptyBoard = ::writeEmptyFixture,
        newBoardId = { ids.removeFirst() },
    )

    private fun writeEmptyFixture(directory: File) {
        writeBoardFixture(directory, 0, 0)
    }

    private fun writeBoardFixture(directory: File, fullScreenCenter: Int, floatingCenter: Int) {
        directory.mkdirs()
        File(directory, "board.json").writeText(
            """{"schemaVersion":1,"viewports":{"fullScreen":{"centerX":$fullScreenCenter,"centerY":${fullScreenCenter + 1},"zoom":${fullScreenCenter + 2}},"floating":{"centerX":$floatingCenter,"centerY":${floatingCenter + 1},"zoom":${floatingCenter + 2}}},"items":[]}""",
        )
    }

    private fun validateFixture(directory: File) {
        BoardStorage(directory).load()
    }

    private fun atomicMove(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }

    private fun copyTree(source: File, destination: File) {
        destination.mkdirs()
        source.listFiles()?.forEach { child ->
            val target = File(destination, child.name)
            if (child.isDirectory) copyTree(child, target)
            else child.copyTo(target, overwrite = true)
        }
    }
}
