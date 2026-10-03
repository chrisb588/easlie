package com.chrisb588.easlie

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal data class StoredBoard(val id: String, val name: String)
internal data class BoardCollection(val activeBoardId: String?, val boards: List<StoredBoard>)

/** Owns the collection index and performs the v0.1 single-board migration. */
internal class BoardCollectionStorage(
    private val root: File,
    private val legacyBoard: File,
    private val copyDirectory: (File, File) -> Unit = ::copyDirectoryRecursively,
    private val validateBoard: (File) -> Unit = { BoardStorage(it).load() },
    private val atomicReplace: (File, File) -> Unit = { source, destination ->
        Os.rename(source.path, destination.path)
    },
    private val createEmptyBoard: (File) -> Unit = { BoardStorage(it).save(BoardSnapshot()) },
    private val newBoardId: () -> String = { UUID.randomUUID().toString() },
) {
    private val boardsDirectory = File(root, "boards")
    private val indexFile = File(root, "boards.index")
    val hasCollection: Boolean get() = indexFile.exists()

    fun initialize(): File? {
        val collection = if (indexFile.exists()) readCollection() else migrateLegacy()
        val activeId = collection.activeBoardId ?: return null
        if (collection.boards.none { it.id == activeId }) return null
        val activeDirectory = directoryFor(activeId)
        // A deleted/missing active board returns to management. Keep the saved identity
        // and board list intact so startup never silently selects or recreates a board.
        if (!activeDirectory.exists()) return null
        require(activeDirectory.isDirectory && File(activeDirectory, "board.json").isFile) {
            "Active board storage is unavailable; collection is read-only."
        }
        // Parse before exposing the new location so a copied unsupported/corrupt manifest
        // cannot turn a failed migration into an apparently successful empty board.
        validateBoard(activeDirectory)
        return activeDirectory
    }

    internal fun directoryFor(id: String): File {
        require(id.matches(ID_PATTERN)) { "Invalid board identifier" }
        return File(boardsDirectory, id)
    }

    internal fun createBoard(name: String, beforeSwitch: () -> Unit = {}): BoardCollection {
        val requestedName = name.trim()
        require(requestedName.isNotEmpty()) { "Board name is empty" }
        val current = if (indexFile.exists()) readCollection() else migrateLegacy()
        val uniqueName = uniqueName(requestedName, current.boards)
        beforeSwitch()
        val id = uniqueId(current.boards)
        val directory = directoryFor(id)
        check(directory.mkdirs()) { "Could not create board storage" }
        try {
            createEmptyBoard(directory)
            validateBoard(directory)
            val updated = BoardCollection(id, listOf(StoredBoard(id, uniqueName)) + current.boards)
            writeCollection(updated)
            return updated
        } catch (failure: Exception) {
            directory.deleteRecursively()
            throw failure
        }
    }

    internal fun openBoard(id: String, beforeSwitch: () -> Unit = {}): BoardCollection {
        val current = readCollection()
        val board = current.boards.firstOrNull { it.id == id }
            ?: error("Board is unavailable")
        val directory = directoryFor(board.id)
        require(directory.isDirectory && File(directory, "board.json").isFile) {
            "Board storage is unavailable; collection is read-only."
        }
        validateBoard(directory)
        beforeSwitch()
        return current.copy(activeBoardId = id, boards = listOf(board) + current.boards.filterNot { it.id == id })
            .also(::writeCollection)
    }

    internal fun renameBoard(id: String, name: String): BoardCollection {
        val requestedName = name.trim()
        require(requestedName.isNotEmpty()) { "Board name is empty" }
        val current = readCollection()
        require(current.boards.any { it.id == id }) { "Board is unavailable" }
        val uniqueName = uniqueName(requestedName, current.boards.filterNot { it.id == id })
        val updated = current.copy(boards = current.boards.map { board ->
            if (board.id == id) board.copy(name = uniqueName) else board
        })
        writeCollection(updated)
        return updated
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
            val empty = BoardCollection(null, emptyList())
            writeCollection(empty)
            return empty
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
        val activeValue = lines.firstOrNull { it.startsWith("activeBoard=") }?.substringAfter('=')
            ?: error("Board collection has no active board")
        val activeId = activeValue.ifEmpty { null }
        require(activeId == null || activeId.matches(ID_PATTERN)) { "Invalid active board identifier" }
        val boards = lines.filter { it.startsWith("board=") }.map { line ->
            val parts = line.substringAfter('=').split('|', limit = 2)
            require(parts.size == 2 && parts[0].matches(ID_PATTERN)) { "Invalid board entry" }
            StoredBoard(parts[0], decodeName(parts[1])).also { require(it.name.isNotBlank()) { "Board name is empty" } }
        }
        require(boards.map { it.id }.distinct().size == boards.size) { "Duplicate board identifier" }
        require(boards.map { it.name }.distinct().size == boards.size) { "Duplicate board name" }
        return BoardCollection(activeId, boards)
    }

    internal fun writeCollection(collection: BoardCollection) {
        require(collection.activeBoardId == null || collection.activeBoardId.matches(ID_PATTERN)) { "Invalid active board identifier" }
        require(collection.boards.map { it.id }.distinct().size == collection.boards.size) { "Duplicate board identifier" }
        require(collection.boards.all { it.id.matches(ID_PATTERN) && it.name.isNotBlank() }) { "Invalid board entry" }
        require(collection.boards.map { it.name }.distinct().size == collection.boards.size) { "Duplicate board name" }
        val encoded = buildString {
            appendLine(MAGIC)
            appendLine("schemaVersion=$SCHEMA_VERSION")
            appendLine("activeBoard=${collection.activeBoardId.orEmpty()}")
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

    private fun uniqueName(requested: String, boards: List<StoredBoard>): String {
        if (boards.none { it.name == requested }) return requested
        var suffix = 2
        while (boards.any { it.name == "$requested ($suffix)" }) suffix++
        return "$requested ($suffix)"
    }

    private fun uniqueId(boards: List<StoredBoard>): String {
        val used = boards.mapTo(mutableSetOf()) { it.id }
        val base = newBoardId()
        require(base.matches(ID_PATTERN)) { "Invalid board identifier" }
        var candidate = base
        var suffix = 2
        while (candidate in used || directoryFor(candidate).exists()) {
            candidate = "$base-$suffix"
            suffix++
        }
        return candidate
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
