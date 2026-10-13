package bayern.kickner.knote.markdown

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownRendererTest {

    @Test
    fun `tables are rendered`() {
        val html = renderMarkdown("| a | b |\n|---|---|\n| 1 | 2 |")

        assertContains(html, "<table>")
        assertContains(html, "<td>1</td>")
    }

    @Test
    fun `strikethrough is rendered`() {
        assertContains(renderMarkdown("~~weg~~"), "<del>weg</del>")
    }

    @Test
    fun `task lists are rendered as disabled checkboxes`() {
        val html = renderMarkdown("- [ ] offen\n- [x] erledigt")

        assertContains(html, "type=\"checkbox\"")
        assertContains(html, "disabled")
        assertContains(html, "checked")
    }

    @Test
    fun `a raw script block is escaped`() {
        val html = renderMarkdown("<script>alert(1)</script>")

        assertFalse(html.contains("<script>"), html)
        assertContains(html, "&lt;script&gt;")
    }

    @Test
    fun `inline raw html is escaped`() {
        assertFalse(renderMarkdown("Text <img src=x onerror=alert(1)> mehr").contains("<img"))
    }

    @Test
    fun `javascript links are emptied`() {
        val html = renderMarkdown("[klick](javascript:alert(1))")

        assertFalse(html.contains("javascript:"), html)
        assertContains(html, "href=\"\"")
    }

    @Test
    fun `data urls are emptied in images and links`() {
        val image = renderMarkdown("![x](data:text/html;base64,PHNjcmlwdD4=)")
        val link = renderMarkdown("[x](data:text/html;base64,PHNjcmlwdD4=)")

        assertContains(image, "src=\"\"")
        assertContains(link, "href=\"\"")
        assertFalse((image + link).contains("data:"))
    }

    @Test
    fun `relative links are kept`() {
        assertContains(renderMarkdown("[intern](/projekte/knote)"), "href=\"/projekte/knote\"")
    }

    @Test
    fun `normal links are kept and escaped`() {
        assertContains(renderMarkdown("[ok](https://example.org/a?b=c&d=e)"), "href=\"https://example.org/a?b=c&amp;d=e\"")
    }

    @Test
    fun `mail links and https images are kept`() {
        assertContains(renderMarkdown("[mail](mailto:a@example.org)"), "href=\"mailto:a@example.org\"")
        assertContains(renderMarkdown("![bild](https://example.org/a.png)"), "src=\"https://example.org/a.png\"")
    }

    @Test
    fun `plain addresses become links`() {
        assertContains(renderMarkdown("see https://example.org/a?b=c"), "href=\"https://example.org/a?b=c\">https://example.org/a?b=c</a>")
        assertContains(renderMarkdown("see www.example.org"), "href=\"http://www.example.org\">www.example.org</a>")
        assertContains(renderMarkdown("mail a@example.org"), "href=\"mailto:a@example.org\">a@example.org</a>")
    }

    @Test
    fun `autolinks do not create script links`() {
        val html = renderMarkdown("javascript:alert(1) and data:text/html;base64,PHNjcmlwdD4=")

        assertFalse(html.contains("<a"), html)
    }

    @Test
    fun `unicode is kept`() {
        assertTrue(renderMarkdown("# Überschrift Ä ß 😀").contains("Überschrift Ä ß 😀"))
    }
}
