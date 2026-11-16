package fi.ferry.messenger.model

/**
 * The web links in a message's text (the iOS app's `MessageRow.linked`, an NSDataDetector): "https://…", "www.…" and a
 * bare domain, only http(s); nothing else a detector finds (a phone number, an address) opens anything. Android has no
 * detector the JVM tests can run (`android.util.Patterns` is a stub there), so this is a pattern, and a bare domain
 * counts only with one of a short list of top-level domains (decision A19).
 */
object Links {
    /** `range`: where the link's words are in the text; `url`: what it opens, always http or https. */
    data class Link(val range: IntRange, val url: String)

    private const val TLDS = "com|org|net|edu|gov|io|co|app|dev|me|info|biz|band|xyz|ai|tv|fm|ly|gg|eu|fi|uk|de|fr|it|es|nl|se|no|dk|" +
        "is|ee|pl|pt|ch|at|be|ie|us|ca|au|nz|jp|kr|br|mx|ar|in|ru|ua|tr"

    /** Made once: it compiles a pattern. Not after a word, "@", "." or "-", so an e-mail address isn't its domain. */
    private val pattern = Regex(
        """https?://[^\s<>"]+|(?<![\w@.-])www\.[^\s<>"]+|""" +
            """(?<![\w@.-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:$TLDS)(?:/[^\s<>"]*)?(?![\w@-])""",
        RegexOption.IGNORE_CASE,
    )

    fun find(text: String): List<Link> = pattern.findAll(text).mapNotNull { match ->
        val start = match.range.first
        var end = match.range.last + 1
        // Sentence punctuation after a link isn't part of it, nor is a ")" its text never opened.
        while (end > start) {
            val last = text[end - 1]
            val shown = text.substring(start, end)
            if (last in ".,;:!?'\"" || last == ')' && shown.count { it == ')' } > shown.count { it == '(' }) end-- else break
        }
        val shown = text.substring(start, end)
        if ('.' !in shown && "://" !in shown) return@mapNotNull null // "www." and nothing after it
        val web = shown.startsWith("http://", ignoreCase = true) || shown.startsWith("https://", ignoreCase = true)
        Link(start until end, if (web) shown else "https://$shown")
    }.toList()
}
