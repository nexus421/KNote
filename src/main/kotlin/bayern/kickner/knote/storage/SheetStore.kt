package bayern.kickner.knote.storage

import bayern.kickner.knote.config.firstLineOf
import kotlinx.serialization.json.Json
import kotnexlib.ResultOf2
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/** Sheet files are pretty printed so they stay readable on disk. Unknown keys are errors. */
private val sheetJson = Json { prettyPrint = true }

/** Why a sheet could not be written or deleted. */
sealed interface StoreError {
    /** Another sheet already uses the path. */
    data object PathTaken : StoreError

    /** The sheet does not exist (any more). */
    data object NotFound : StoreError

    /** The sheet was changed by someone else since it was loaded. */
    data object Conflict : StoreError

    /** The file system refused. [reason] is safe to log. */
    data class WriteFailed(val reason: String) : StoreError
}

/**
 * Keeps all sheets in memory, keyed by path, and writes every change through to [dir] atomically. A sheet is only
 * ever found through this map, never through the file system, so no URL can reach a file directly.
 */
class SheetStore private constructor(private val dir: Path, initial: Map<String, Sheet>) {

    private val byPath = ConcurrentHashMap(initial)

    /** All sheets, sorted by path. */
    val all: List<Sheet> get() = byPath.values.sortedBy { it.path }

    /** Number of sheets. */
    val size: Int get() = byPath.size

    /** The sheet at [path], or null. */
    fun find(path: String): Sheet? = byPath[path]

    /** The sheet with [id], or null. A linear scan, fine for the few hundred sheets KNote is made for. */
    fun findById(id: String): Sheet? = byPath.values.firstOrNull { it.id == id }

    /**
     * Writes [sheet] to disk first and only then to memory, so memory never shows unsaved state. A changed path
     * renames the sheet: the old path is gone afterwards.
     *
     * @param expectedUpdated For an edit: the [Sheet.updated] the editor was loaded with. The save fails with
     * [StoreError.Conflict] if the stored sheet has changed since, or [StoreError.NotFound] if it was deleted.
     * Null for a new sheet.
     */
    @Synchronized
    fun save(sheet: Sheet, expectedUpdated: Long? = null): ResultOf2<Sheet, StoreError> {
        if (expectedUpdated != null) {
            val stored = findById(sheet.id) ?: return ResultOf2.Failure(StoreError.NotFound)
            if (stored.updated != expectedUpdated) return ResultOf2.Failure(StoreError.Conflict)
        }
        val other = byPath[sheet.path]
        val pathTaken = other != null && other.id != sheet.id
        if (pathTaken) return ResultOf2.Failure(StoreError.PathTaken)

        val written = writeAtomically(sheet)
        if (written.isFailure) return ResultOf2.Failure(StoreError.WriteFailed("sheet ${sheet.id}: ${written.exceptionOrNull()?.message}"))

        byPath.values.removeIf { it.id == sheet.id && it.path != sheet.path }
        byPath[sheet.path] = sheet
        return ResultOf2.Success(sheet)
    }

    /** Deletes the sheet with [id] from disk and memory and returns it. */
    @Synchronized
    fun delete(id: String): ResultOf2<Sheet, StoreError> {
        val sheet = findById(id) ?: return ResultOf2.Failure(StoreError.NotFound)
        val deleted = runCatching { Files.deleteIfExists(dir.resolve("$id.json")) }
        if (deleted.isFailure) return ResultOf2.Failure(StoreError.WriteFailed("sheet $id: ${deleted.exceptionOrNull()?.message}"))
        byPath.remove(sheet.path)
        return ResultOf2.Success(sheet)
    }

    /**
     * The temp file lies in the target directory, because a move is only atomic within one file system. A failed
     * write removes it again. One left behind by a crash is ignored by [load].
     */
    private fun writeAtomically(sheet: Sheet): Result<Unit> {
        val tmp = runCatching { Files.createTempFile(dir, sheet.id, ".tmp") }.getOrElse { return Result.failure(it) }
        return runCatching {
            Files.writeString(tmp, sheetJson.encodeToString(Sheet.serializer(), sheet))
            Files.move(tmp, dir.resolve("${sheet.id}.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        }.onFailure { runCatching { Files.deleteIfExists(tmp) } }
    }

    companion object {
        /**
         * Loads every `*.json` in [dir] and creates [dir] if it is missing. Fails with all problems at once:
         * unreadable files, unknown keys, a file name that does not match the id, invalid paths and paths used by
         * more than one file. Other files, such as `*.tmp` left behind by a crash, are ignored.
         */
        fun load(dir: Path): ResultOf2<SheetStore, List<String>> {
            val files = runCatching {
                Files.createDirectories(dir)
                dir.listDirectoryEntries("*.json")
            }.getOrElse { return ResultOf2.Failure(listOf("$dir cannot be read: ${it.message}")) }

            val problems = mutableListOf<String>()
            val loaded = files.mapNotNull { file ->
                runCatching { file to sheetJson.decodeFromString(Sheet.serializer(), file.readText()) }
                    .onFailure { problems += "${file.fileName}: ${firstLineOf(it.message)}" }
                    .getOrNull()
            }
            loaded.filter { (file, sheet) -> file.fileName.toString() != "${sheet.id}.json" }
                .forEach { (file, sheet) -> problems += "${file.fileName}: file name does not match id ${sheet.id}" }
            val sheets = loaded.map { it.second }
            sheets.filter { isValidSheetPath(it.path).not() }.forEach { problems += "${it.id}.json: invalid path '${it.path}'" }
            sheets.groupBy { it.path }.filterValues { it.size > 1 }
                .forEach { (path, duplicates) -> problems += "path '$path' is used by ${duplicates.joinToString { it.id }}" }

            if (problems.isNotEmpty()) return ResultOf2.Failure(problems)
            return ResultOf2.Success(SheetStore(dir, sheets.associateBy { it.path }))
        }

        /** A new random id, also the file name. */
        fun newId(): String = UUID.randomUUID().toString()
    }
}
