package com.numbear.manjuan.core

object HtmlText {
    fun toPlain(html: String): String {
        var text = html
        text = text.replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), " ")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote)>"), "\n")
        text = text.replace(Regex("(?i)<[^>]+>"), "")
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
