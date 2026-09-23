package com.numbear.manjuan.core

object HtmlText {
    private val imageTag = Regex("(?is)<(img|image)\\b([^>]*)>")
    private val hrefAttr = Regex("(?i)\\b(?:src|href)\\s*=\\s*['\"]([^'\"]+)['\"]")

    sealed class Block {
        data class Text(val html: String) : Block()
        data class Image(val href: String) : Block()
    }

    fun blocks(html: String): List<Block> {
        val blocks = ArrayList<Block>()
        var cursor = 0
        for (match in imageTag.findAll(html)) {
            if (match.range.first > cursor) {
                blocks += Block.Text(html.substring(cursor, match.range.first))
            }
            val href = hrefAttr.find(match.groupValues[2])?.groupValues?.get(1)
                ?.substringBefore('#')
                ?.trim()
                .orEmpty()
            if (href.isNotEmpty() && !href.startsWith("data:", ignoreCase = true)) {
                blocks += Block.Image(href)
            }
            cursor = match.range.last + 1
        }
        if (cursor < html.length) blocks += Block.Text(html.substring(cursor))
        return blocks
    }

    fun toPlain(html: String): String {
        var text = html.replace("\uFFFD", "")
        text = text.replace(Regex("""(?i)"\d{3,6}"\s*alt\s*=\s*"[^"]*"\s*/?>?"""), " ")
        text = text.replace(Regex("""(?i)"\d{3,6}"\s*alt\s*=\s*'[^']*'\s*/?>?"""), " ")
        text = text.replace(Regex("(?is)<(script|style|guide)\\b[^>]*>.*?</\\1>"), " ")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote)>"), "\n")
        text = text.replace(Regex("(?is)<[^>]+>"), " ")
        text = text.replace(Regex("(?i)<img\\b[^>\\n]{0,240}"), " ")
        text = text.replace(Regex("(?i)</?mbp:[^>\\s]{0,80}"), " ")
        text = text.replace(Regex("(?i)\\bmbp:[a-z0-9:_-]*"), " ")
        text = text.replace(Regex("(?i)\\balt\\s*=\\s*\"[^\"]*\""), " ")
        text = text.replace(Regex("(?i)\\balt\\s*=\\s*'[^']*'"), " ")
        text = text.replace(Regex("(?i)\\balt\\s*=\\s*[^\\s\"'<>]*"), " ")
        text = text.replace(Regex("(?i)\\b(?:recindex|filepos)\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|\\S*)"), " ")
        text = text.replace(Regex("""(?i)"\d{3,6}"\s*/?>"""), " ")
        text = text.replace("/>", " ")
        text = unescape(text)
        text = text.replace('\u00A0', ' ')
        text = text.replace(Regex("[ \\t\\u3000]+"), " ")
        text = text.replace(Regex(" *\\n *"), "\n")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        return text.trim()
    }

    private fun unescape(value: String): String {
        val named = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
        )
        return Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(value) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) -> decodeCode(body.drop(2), 16)
                body.startsWith("#") -> decodeCode(body.drop(1), 10)
                else -> named[body.lowercase()] ?: match.value
            }
        }
    }

    private fun decodeCode(raw: String, radix: Int): String {
        val code = raw.toIntOrNull(radix) ?: return ""
        return if (code <= 0x10FFFF) String(Character.toChars(code)) else ""
    }
}
