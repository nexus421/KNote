package bayern.kickner.knote.settings

import bayern.kickner.knote.crypto.encryptContent
import bayern.kickner.knote.plainSheet
import bayern.kickner.knote.tempDir
import kotnexlib.ResultOf2
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SiteSettingsTest {

    private val file = tempDir().resolve("settings.json")

    @Test
    fun `a missing file means the defaults and is not created`() {
        val settings = assertIs<ResultOf2.Success<SettingsStore>>(SettingsStore.load(file)).value

        assertEquals(StartPageList.NONE, settings.current.startPageList)
        assertFalse(Files.exists(file))
    }

    @Test
    fun `saved settings survive a reload`() {
        val settings = assertIs<ResultOf2.Success<SettingsStore>>(SettingsStore.load(file)).value

        settings.save(SiteSettings(StartPageList.ALL)).getOrThrow()

        assertContains(file.readText(), "\"startPageList\": \"all\"")
        assertEquals(StartPageList.ALL, assertIs<ResultOf2.Success<SettingsStore>>(SettingsStore.load(file)).value.current.startPageList)
        assertTrue(Files.list(file.parent).use { files -> files.noneMatch { it.toString().endsWith(".tmp") } })
    }

    @Test
    fun `a broken file or unknown key is a failure`() {
        file.writeText("{")
        assertIs<ResultOf2.Failure<String>>(SettingsStore.load(file))

        file.writeText("""{"startPageList": "none", "extra": 1}""")
        assertContains(assertIs<ResultOf2.Failure<String>>(SettingsStore.load(file)).value, "extra")
    }

    @Test
    fun `each choice lists the right sheets and never a disabled one`() {
        val open = plainSheet("offen")
        val locked = plainSheet("privat").copy(content = encryptContent("x", "password-1234"))
        val disabled = plainSheet("aus").copy(enabled = false)
        val sheets = listOf(open, locked, disabled)

        fun listed(choice: StartPageList) = sheets.filter { choice.includes(it) }.map { it.path }

        assertEquals(emptyList(), listed(StartPageList.NONE))
        assertEquals(listOf("offen"), listed(StartPageList.UNPROTECTED))
        assertEquals(listOf("privat"), listed(StartPageList.PROTECTED))
        assertEquals(listOf("offen", "privat"), listed(StartPageList.ALL))
    }
}
