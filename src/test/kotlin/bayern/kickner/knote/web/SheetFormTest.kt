package bayern.kickner.knote.web

import bayern.kickner.knote.i18n.EnglishTexts
import bayern.kickner.knote.i18n.GermanTexts
import io.ktor.http.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun form(path: String = "einkauf", title: String = "Einkauf", markdown: String = "x") =
    SheetForm(path, title, markdown, "", "", null, null, null)

class SheetFormTest {

    private val t = EnglishTexts

    @Test
    fun `fields are trimmed and line breaks normalized`() {
        val parameters = parametersOf(
            "path" to listOf(" einkauf "),
            "title" to listOf(" Einkauf "),
            "markdown" to listOf("a\r\nb\r\n"),
            "protection" to listOf("keep"),
            "updated" to listOf("42"),
        )

        val form = parameters.toSheetForm()

        assertEquals("einkauf", form.path)
        assertEquals("Einkauf", form.title)
        assertEquals("a\nb\n", form.markdown)
        assertEquals(ProtectionChoice.KEEP, form.protection)
        assertEquals(42L, form.updated)
        assertNull(form.currentPassword)
        assertFalse(form.preview)
    }

    @Test
    fun `the preview button is recognized`() {
        assertTrue(parametersOf(ACTION_FIELD to listOf(PREVIEW_ACTION)).toSheetForm().preview)
        assertFalse(parametersOf(ACTION_FIELD to listOf("save")).toSheetForm().preview)
    }

    @Test
    fun `missing and unknown values stay empty`() {
        val form = parametersOf("protection" to listOf("everything"), "updated" to listOf("x")).toSheetForm()

        assertEquals("", form.path)
        assertNull(form.protection)
        assertNull(form.updated)
    }

    @Test
    fun `valid fields have no issues`() {
        assertTrue(validateFields(t, form(title = "a".repeat(100)), maxSheetBytes = 1024).isEmpty())
    }

    @Test
    fun `each field rule has its own message`() {
        assertEquals(listOf(t.invalidPath(128)), validateFields(t, form(path = "Einkauf"), 1024))
        assertEquals(listOf("Title 1 to 100 characters"), validateFields(t, form(title = ""), 1024))
        assertEquals(listOf("Title 1 to 100 characters"), validateFields(t, form(title = "a".repeat(101)), 1024))
        assertEquals(listOf("Title must not contain control characters"), validateFields(t, form(title = "a\u0007b"), 1024))
    }

    @Test
    fun `the markdown limit counts utf-8 bytes`() {
        // 512 characters, but 1024 bytes
        assertTrue(validateFields(t, form(markdown = "ä".repeat(512)), 1024).isEmpty())
        assertEquals(listOf("Content too large (at most 1024 bytes)"), validateFields(t, form(markdown = "ä".repeat(512) + "a"), 1024))
    }

    @Test
    fun `password rules`() {
        assertTrue(validateNewPassword(t, "a".repeat(12), "a".repeat(12), 12).isEmpty())
        assertEquals(listOf("Password at least 12 characters"), validateNewPassword(t, "a".repeat(11), "a".repeat(11), 12))
        assertEquals(listOf("Password at most 1024 characters"), validateNewPassword(t, "a".repeat(1025), "a".repeat(1025), 12))
        assertEquals(listOf("Passwords do not match"), validateNewPassword(t, "a".repeat(12), "b".repeat(12), 12))
    }

    @Test
    fun `messages follow the language`() {
        assertEquals(listOf("Passwort mindestens 12 Zeichen"), validateNewPassword(GermanTexts, "a", "a", 12))
    }
}
