package com.numbear.manjuan.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

object WebDavXml {
    fun parse(xml: String, requestPath: String): List<WebDavEntry> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // Desktop JREs honor this. Android's DocumentBuilderFactory rejects it on every
        // API level (ParserConfigurationException). Listing must still parse the PROPFIND
        // body; otherwise browse-after-save is reported as 「无法连接服务器」.
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        } catch (_: ParserConfigurationException) {
        }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        val responses = document.documentElement.elementsByLocal("response")
            .filter { it.parentNode == document.documentElement || it.localName.equals("response", true) }
        val entries = ArrayList<WebDavEntry>()
        val self = normalize(requestPath)
        for (node in responses) {
            val href = node.elementsByLocal("href").firstOrNull()?.textContent ?: continue
            val path = WebDavPaths.hrefToPath(href, requestPath)
            val display = node.elementsByLocal("displayname").firstOrNull()?.textContent?.trim().orEmpty()
            val collection = node.elementsByLocal("collection").isNotEmpty()
            val size = node.elementsByLocal("getcontentlength").firstOrNull()?.textContent?.toLongOrNull() ?: 0L
            if (path == self || path.trimEnd('/') == self.trimEnd('/')) continue
            val name = display.ifBlank { path.trimEnd('/').substringAfterLast('/') }.ifBlank { path }
            entries += WebDavEntry(
                path = if (collection) path.ensureSlash() else path,
                name = name,
                directory = collection,
                size = size,
            )
        }
        return entries.sortedWith(compareBy<WebDavEntry> { !it.directory }.thenBy(NaturalSort) { it.name })
    }

    private fun normalize(path: String): String {
        val cleaned = path.trim().ifBlank { "/" }
        return if (cleaned.startsWith("/")) cleaned else "/$cleaned"
    }

    private fun String.ensureSlash(): String = if (endsWith("/")) this else "$this/"

}

private fun Element.elementsByLocal(local: String): List<Element> {
    val found = ArrayList<Element>()
    fun walk(node: Node) {
        val children = node.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) {
                val name = child.localName ?: child.nodeName
                if (name.equals(local, true) || name.substringAfter(':').equals(local, true)) {
                    found += child
                }
                walk(child)
            }
        }
    }
    walk(this)
    return found
}
