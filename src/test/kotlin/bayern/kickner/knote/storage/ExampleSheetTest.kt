package bayern.kickner.knote.storage

import bayern.kickner.knote.FIXED_NOW
import bayern.kickner.knote.tempDir
import kotnexlib.ResultOf2
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExampleSheetTest {

    private fun open(dir: Path) = assertIs<ResultOf2.Success<SheetStore>>(openStore(dir) { FIXED_NOW }).value

    @Test
    fun `a fresh install gets the example sheet`() {
        val dir = tempDir().resolve("data/sheets")

        val store = open(dir)

        val example = store.find(EXAMPLE_PATH)
        assertEquals(EXAMPLE_TITLE, example?.title)
        assertEquals(FIXED_NOW, example?.updated)
        assertContains((example?.content as SheetContent.Plain).markdown, "## Task lists")
        assertEquals(1, open(dir).size)
    }

    @Test
    fun `a deleted example does not come back`() {
        val dir = tempDir().resolve("sheets")
        val store = open(dir)
        store.delete(store.find(EXAMPLE_PATH)?.id.orEmpty())

        assertNull(open(dir).find(EXAMPLE_PATH))
    }

    @Test
    fun `an existing directory gets no example`() {
        assertEquals(0, open(tempDir()).size)
    }

    @Test
    fun `the example is a valid sheet`() {
        assertEquals(true, isValidSheetPath(exampleSheet(FIXED_NOW).path))
    }
}
