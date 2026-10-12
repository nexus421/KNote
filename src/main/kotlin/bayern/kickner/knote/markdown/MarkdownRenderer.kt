package bayern.kickner.knote.markdown

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.DefaultUrlSanitizer
import org.commonmark.renderer.html.HtmlRenderer

private val extensions = listOf(
    TablesExtension.create(),
    StrikethroughExtension.create(),
    TaskListItemsExtension.create(),
)

private val parser = Parser.builder().extensions(extensions).build()

/**
 * Raw HTML in the Markdown is escaped, links and images are limited to three protocols. The protocol list is set
 * explicitly because the default sanitizer also allows `data:` URLs.
 */
private val renderer = HtmlRenderer.builder()
    .extensions(extensions)
    .escapeHtml(true)
    .sanitizeUrls(true)
    .urlSanitizer(DefaultUrlSanitizer(listOf("http", "https", "mailto")))
    .build()

/**
 * Renders [markdown] to HTML that is safe to embed: CommonMark plus tables, strikethrough and task lists. Parser
 * and renderer are immutable and shared by all threads.
 */
fun renderMarkdown(markdown: String): String = renderer.render(parser.parse(markdown))
