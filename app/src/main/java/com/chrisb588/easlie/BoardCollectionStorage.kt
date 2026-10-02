package com.chrisb588.easlie

import android.system.Os
import java.io.File
import java.io.FileOutputStream

internal data class StoredBoard(val id: String, val name: String)
internal data class BoardCollection(val activeBoardId: String, val boards: List<StoredBoard>)

/** Owns the collection index and performs the v0.1 single-board migration. */
internal class BoardCollectionStorage(
    private val root: File,
    private val legacyBoard: File,
    private val copyDirectory: (File, File) -> Unit = ::copyDirectoryRecursively,
    private val validateBoard: (File) -> Unit = { BoardStorage(it).load() },
    private val atomicReplace: (File, File) -> Unit = { source, destination ->
        Os.rename(source.path, destination.path)
    },
) {
    private val boardsDirectory = File(root, "boards")
    private val indexFile = File(root, "boards.index")
    val hasCollection: Boolean get() = indexFile.exists()

    fun initialize(): File {
        val collection = if (indexFile.exists()) readCollection() else migrateLegacy()
        val activeDirectory = File(boardsDirectory, collection.activeBoardId)
        require(activeDirectory.isDirectory && File(activeDirectory, "board.json").isFile) {
            "Active board storage is unavailable; collection is read-only."
        }
        // Parse before exposing the new location so a copied unsupported/corrupt manifest
        // cannot turn a failed migration into an apparently successful empty board.
        validateBoard(activeDirectory)
        return activeDirectory
    }

    private fun migrateLegacy(): BoardCollection {
        // A fixed ID means an interrupted attempt always resumes the same destination.
        val id = MIGRATED_BOARD_ID
        val destination = File(boardsDirectory, id)
        boardsDirectory.mkdirs()
        if (destination.exists() && !indexFile.exists()) {
            check(destination.deleteRecursively()) { "Could not clear an incomplete migration; original storage is retained." }
        }

        if (File(legacyBoard, "board.json").isFile) {
            validateBoard(legacyBoard)
            copyDirectory(legacyBoard, destination)
        } else {
            val legacyFiles = if (legacyBoard.exists()) {
                legacyBoard.listFiles()?.toList() ?: error("Cannot inspect legacy board files; original storage is retained.")
            } else emptyList()
            require(legacyFiles.isEmpty()) {
                "Legacy board files exist without a manifest; original storage is retained."
            }
            destination.mkdirs()
            BoardStorage(destination).save(BoardSnapshot())
        }
        validateBoard(destination)

        val collection = BoardCollection(id, listOf(StoredBoard(id, "Board 1")))
        writeCollection(collection)
        return collection
    }

    internal fun readCollection(): BoardCollection {
        val lines = indexFile.readLines(Charsets.UTF_8)
        require(lines.firstOrNull() == MAGIC) { "Unsupported board collection; storage is read-only." }
        val version = lines.firstOrNull { it.startsWith("schemaVersion=") }
            ?.substringAfter('=')?.toIntOrNull()
        require(version == SCHEMA_VERSION) { "Unsupported board collection schema; storage is read-only." }
        val activeId = lines.firstOrNull { it.startsWith("activeBoard=") }?.substringAfter('=')
            ?: error("Board collection has no active board")
        require(activeId.matches(ID_PATTERN)) { "Invalid active board identifier" }
        val boards = lines.filter { it.startsWith("board=") }.map { line ->
            val parts = line.substringAfter('=').split('|', limit = 2)
            require(parts.size == 2 && parts[0].matches(ID_PATTERN)) { "Invalid board entry" }
            StoredBoard(parts[0], decodeName(parts[1])).also { require(it.name.isNotBlank()) { "Board name is empty" } }
        }
        require(boards.isNotEmpty() && boards.any { it.id == activeId }) { "Active board is unavailable" }
        require(boards.map { it.id }.distinct().size == boards.size) { "Duplicate board identifier" }
        return BoardCollection(activeId, boards)
    }

    private fun writeCollection(collection: BoardCollection) {
        require(collection.activeBoardId.matches(ID_PATTERN)) { "Invalid active board identifier" }
        require(collection.boards.isNotEmpty() && collection.boards.any { it.id == collection.activeBoardId }) {
            "Active board is unavailable"
        }
        require(collection.boards.map { it.id }.distinct().size == collection.boards.size) { "Duplicate board identifier" }
        require(collection.boards.all { it.id.matches(ID_PATTERN) && it.name.isNotBlank() }) { "Invalid board entry" }
        val encoded = buildString {
            appendLine(MAGIC)
            appendLine("schemaVersion=$SCHEMA_VERSION")
            appendLine("activeBoard=${collection.activeBoardId}")
            collection.boards.forEach { board ->
                val name = encodeName(board.name)
                appendLine("board=${board.id}|$name")
            }
        }
        root.mkdirs()
        val temporary = File(root, "boards.index.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(encoded.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            atomicReplace(temporary, indexFile)
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val MAGIC = "easlie-board-collection"
        const val SCHEMA_VERSION = 1
        const val MIGRATED_BOARD_ID = "00000000-0000-4000-8000-000000000001"
        val ID_PATTERN = Regex("[a-zA-Z0-9-]+")

        fun encodeName(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

        fun decodeName(value: String): String {
            require(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid board name encoding"
            }
            return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
                .toString(Charsets.UTF_8)
        }
    }
}

private fun copyDirectoryRecursively(source: File, destination: File) {
    if (!source.isDirectory) error("Legacy board directory is unavailable")
    destination.mkdirs()
    val children = source.listFiles()?.toList() ?: error("Cannot inspect legacy board files")
    children.forEach { child ->
        val target = File(destination, child.name)
        if (child.isDirectory) copyDirectoryRecursively(child, target)
        else child.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
        }
    }
}
