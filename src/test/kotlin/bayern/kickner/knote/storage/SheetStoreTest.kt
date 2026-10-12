package bayern.kickner.knote.storage

import bayern.kickner.knote.crypto.encryptContent
import bayern.kickner.knote.emptyStore
import bayern.kickner.knote.plainSheet
import bayern.kickner.knote.tempDir
import kotnexlib.ResultOf2
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sheetFile(id: String, path: String, extra: String = "") =
    """{"id":"$id","path":"$path","title":"t","content":{"type":"plain","markdown":""},"createdBy":"s","created":1,"updated":1$extra}"""

class SheetPathTest {

    @Test
    fun `valid paths are accepted`() {
        listOf("einkauf", "projekte/knote", "a-b/c-d/e", "a".repeat(128)).forEach { assertTrue(isValidSheetPath(it), it) }
    }

    @Test
    fun `invalid paths are rejected`() {
        val invalid = listOf("", "/einkauf", "einkauf/", "../etc/passwd", "a//b", "_admin", "Einkauf", "ä", "a b", "a/./b", "a%2fb", "a".repeat(129))
        invalid.forEach { assertFalse(isValidSheetPath(it), "'$it' must be rejected") }
    }
}

class SheetStoreTest {

    private val dir: Path = tempDir()
    private val store = emptyStore(dir)

    @Test
    fun `saved sheets survive a reload`() {
        val plain = plainSheet("einkauf")
        val secret = plainSheet("privat/notiz").copy(content = encryptContent("geheim", "password-1234"))
        assertIs<ResultOf2.Success<Sheet>>(store.save(plain))
        assertIs<ResultOf2.Success<Sheet>>(store.save(secret))

        val reloaded = emptyStore(dir)

        assertEquals(plain, reloaded.find("einkauf"))
        assertEquals(secret, reloaded.find("privat/notiz"))
        assertEquals(listOf("einkauf", "privat/notiz"), reloaded.all.map { it.path })
    }

    @Test
    fun `a sheet file names its content type and stays readable`() {
        val sheet = plainSheet("einkauf", markdown = "# Einkauf\n- Brot")
        store.save(sheet)

        val json = dir.resolve("${sheet.id}.json").readText()

        assertContains(json, "\"type\": \"plain\"")
        assertContains(json, "\"markdown\": \"# Einkauf\\n- Brot\"")
        assertContains(json, "\n    \"path\": \"einkauf\"")
    }

    @Test
    fun `a path used by another sheet is rejected`() {
        store.save(plainSheet("einkauf"))

        val result = store.save(plainSheet("einkauf"))

        assertEquals(StoreError.PathTaken, assertIs<ResultOf2.Failure<StoreError>>(result).value)
        assertEquals(1, dir.listDirectoryEntries("*.json").size)
    }

    @Test
    fun `a rename keeps the id and drops the old path`() {
        val sheet = plainSheet("einkauf")
        store.save(sheet)

        store.save(sheet.copy(path = "einkauf-neu", updated = sheet.updated + 1), expectedUpdated = sheet.updated)

        assertNull(store.find("einkauf"))
        assertEquals("einkauf-neu", store.find("einkauf-neu")?.path)
        assertEquals(sheet.id, store.findById(sheet.id)?.id)
        assertEquals(1, dir.listDirectoryEntries("*.json").size)
    }

    @Test
    fun `an outdated edit is a conflict`() {
        val sheet = plainSheet("einkauf")
        store.save(sheet)
        store.save(sheet.copy(title = "first", updated = sheet.updated + 1), expectedUpdated = sheet.updated)

        val second = store.save(sheet.copy(title = "second", updated = sheet.updated + 2), expectedUpdated = sheet.updated)

        assertEquals(StoreError.Conflict, assertIs<ResultOf2.Failure<StoreError>>(second).value)
        assertEquals("first", store.find("einkauf")?.title)
    }

    @Test
    fun `an edit of a deleted sheet is not found and does not bring it back`() {
        val sheet = plainSheet("einkauf")
        store.save(sheet)
        store.delete(sheet.id)

        val result = store.save(sheet.copy(title = "edited"), expectedUpdated = sheet.updated)

        assertEquals(StoreError.NotFound, assertIs<ResultOf2.Failure<StoreError>>(result).value)
        assertNull(store.find("einkauf"))
        assertTrue(dir.listDirectoryEntries("*.json").isEmpty())
    }

    @Test
    fun `delete removes file and entry`() {
        val sheet = plainSheet("einkauf")
        store.save(sheet)

        assertEquals(sheet, assertIs<ResultOf2.Success<Sheet>>(store.delete(sheet.id)).value)
        assertNull(store.find("einkauf"))
        assertTrue(dir.listDirectoryEntries().isEmpty())
        assertEquals(StoreError.NotFound, assertIs<ResultOf2.Failure<StoreError>>(store.delete(sheet.id)).value)
    }

    @Test
    fun `a failed write changes nothing and leaves no temp file`() {
        val sheet = plainSheet("einkauf")
        // A directory in place of the target file makes the move fail
        Files.createDirectory(dir.resolve("${sheet.id}.json"))
        Files.writeString(dir.resolve("${sheet.id}.json").resolve("blocker"), "x")

        val result = store.save(sheet)

        assertIs<StoreError.WriteFailed>(assertIs<ResultOf2.Failure<StoreError>>(result).value)
        assertNull(store.find("einkauf"))
        assertTrue(dir.listDirectoryEntries("*.tmp").isEmpty())
    }

    @Test
    fun `left over temp files are ignored`() {
        store.save(plainSheet("einkauf"))
        dir.resolve("crash.tmp").writeText("garbage")

        assertEquals(1, emptyStore(dir).size)
    }

    @Test
    fun `a missing directory is created`() {
        val missing = tempDir().resolve("data/sheets")

        assertIs<ResultOf2.Success<SheetStore>>(SheetStore.load(missing))
        assertTrue(Files.isDirectory(missing))
    }

    @Test
    fun `unknown keys are rejected`() {
        dir.resolve("x.json").writeText(sheetFile("x", "a", extra = ""","extra":true"""))

        assertContains(problems().single(), "unknown key 'extra'")
    }

    @Test
    fun `a file name that does not match the id is rejected`() {
        dir.resolve("other.json").writeText(sheetFile("z", "a"))

        assertContains(problems().single(), "does not match id z")
    }

    @Test
    fun `an invalid path in a file is rejected`() {
        dir.resolve("y.json").writeText(sheetFile("y", "../etc"))

        assertContains(problems().single(), "invalid path '../etc'")
    }

    @Test
    fun `a path in two files is rejected`() {
        dir.resolve("a.json").writeText(sheetFile("a", "same"))
        dir.resolve("b.json").writeText(sheetFile("b", "same"))

        assertContains(problems().single(), "path 'same' is used by")
    }

    @Test
    fun `all problems are reported at once`() {
        dir.resolve("broken.json").writeText("{")
        dir.resolve("other.json").writeText(sheetFile("z", "a"))
        dir.resolve("y.json").writeText(sheetFile("y", "Bad"))

        assertEquals(3, problems().size)
    }

    private fun problems(): List<String> = assertIs<ResultOf2.Failure<List<String>>>(SheetStore.load(dir)).value
}
