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
        val migratedDirectory = first.initialize()

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
        val migrated = storage.initialize()
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

    private fun newStorage(root: File, legacy: File) = BoardCollectionStorage(
        root, legacy, validateBoard = ::validateFixture, atomicReplace = ::atomicMove,
    )

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
