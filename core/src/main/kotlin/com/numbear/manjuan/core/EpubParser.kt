package com.numbear.manjuan.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

object EpubParser {
    fun parse(file: File): NovelContent {
        ZipFile(file, Charsets.UTF_8).use { zip ->
            return parse({ path -> zip.readBytes(path) }, file.nameWithoutExtension, Int.MAX_VALUE)
        }
    }

    /**
     * [maxSpineHtml] stops after that many HTML spine items. Later chapters stay
     * unread so a remote EPUB can open from the first chapter alone.
     */
    fun parse(read: (String) -> ByteArray?, fallbackTitle: String, maxSpineHtml: Int): NovelContent {
        val container = read("META-INF/container.xml")?.let(::decodeXml)
            ?: throw UnsupportedBookException("EPUB 缺少 META-INF/container.xml")
        val opfPath = Regex("""full-path\s*=\s*"([^"]+)"""")
            .find(container)?.groupValues?.get(1)?.substringBefore('#')
            ?: throw UnsupportedBookException("EPUB 没有 OPF 路径")
        val opfBytes = read(opfPath) ?: throw UnsupportedBookException("EPUB 缺少 OPF：$opfPath")
        val doc = parseXml(decodeXml(opfBytes))
        val elements = doc.documentElement.descendants()
        val title = elements.firstText("title").ifBlank { fallbackTitle }
        val author = elements.firstText("creator")
        val manifest = elements.filter { it.localName == "item" }.associate { item ->
            item.attr("id") to ManifestItem(
                href = item.attr("href"),
                mediaType = item.attr("media-type"),
            )
        }
        val spine = elements.filter { it.localName == "itemref" }.map { it.attr("idref") }
        if (spine.isEmpty()) throw UnsupportedBookException("EPUB 目录是空的")
        val base = opfPath.substringBeforeLast('/', "")
        val chapters = ArrayList<NovelChapter>()
        var htmlUsed = 0
        var moreSpine = false
        for (id in spine) {
            val item = manifest[id] ?: continue
            if (!isHtml(item)) continue
            if (htmlUsed >= maxSpineHtml) {
                moreSpine = true
                break
            }
            htmlUsed++
            val href = resolveZipPath(base, item.href.substringBefore('#'))
            val bytes = read(href) ?: continue
            val chapterDir = href.substringBeforeLast('/', "")
            val spans = ArrayList<NovelSpan>()
            for (block in HtmlText.blocks(decodeXml(bytes))) {
                when (block) {
                    is HtmlText.Block.Text -> {
                        val plain = HtmlText.toPlain(block.html)
                        if (plain.isNotBlank()) spans += NovelSpan.Prose(plain)
                    }
                    is HtmlText.Block.Image -> {
                        if (block.href.isBlank()) continue
                        val imagePath = resolveZipPath(chapterDir, block.href)
                        val image = read(imagePath) ?: continue
                        val payload = ImageSniff.extract(image) ?: continue
                        spans += NovelSpan.Plate(payload)
                    }
                }
            }
            if (spans.isEmpty()) continue
            val plain = spans.filterIsInstance<NovelSpan.Prose>().joinToString("\n") { it.text }
            val heading = Regex("(?m)^(.{1,40})$").find(plain)?.value?.trim().orEmpty()
            val chapterTitle = heading.ifBlank {
                if (spans.any { it is NovelSpan.Plate }) "彩页" else href.substringAfterLast('/')
            }
            chapters += NovelChapter(chapterTitle, plain, spans)
        }
        if (chapters.isEmpty()) throw UnsupportedBookException("EPUB 里没有可阅读的章节")
        return NovelContent(title, author, chapters, more = moreSpine)
    }

    private fun isHtml(item: ManifestItem): Boolean {
        val type = item.mediaType.lowercase()
        if (type.contains("html") || type.contains("xml") || type.contains("text")) return true
        val ext = FormatDetector.extension(item.href)
        return ext == "xhtml" || ext == "html" || ext == "htm"
    }

    private data class ManifestItem(val href: String, val mediaType: String)
}

internal fun resolveZipPath(baseDir: String, href: String): String {
    val clean = href.substringBefore('#').replace('\\', '/')
    if (clean.startsWith("/")) return clean.trimStart('/')
    val stack = baseDir.split('/').filter { it.isNotEmpty() }.toMutableList()
    for (part in clean.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            else -> stack += part
        }
    }
    return stack.joinToString("/")
}

private fun ZipFile.readBytes(path: String): ByteArray? {
    val entry = getEntry(path) ?: entries().toList().firstOrNull {
        it.name.equals(path, ignoreCase = true) || it.name.endsWith("/$path")
    } ?: return null
    return getInputStream(entry).use { it.readBytes() }
}

internal fun decodeXml(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
    }
    val head = bytes.copyOfRange(0, minOf(bytes.size, 160)).toString(Charsets.ISO_8859_1)
    val declared = Regex("""encoding\s*=\s*["']([^"']+)["']""").find(head)?.groupValues?.get(1)
    val charset = try {
        if (declared.isNullOrBlank()) Charsets.UTF_8 else charset(declared)
    } catch (_: Exception) {
        Charsets.UTF_8
    }
    return String(bytes, charset)
}

private fun parseXml(xml: String): org.w3c.dom.Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    try {
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    } catch (_: ParserConfigurationException) {
        // Android's DocumentBuilderFactory rejects this feature on every API level.
    }
    try {
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    } catch (_: Exception) {
        // Some parsers do not expose this feature. Secure processing still applies.
    }
    return factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
}

private fun Element.descendants(): List<Element> {
    val found = ArrayList<Element>()
    fun walk(node: Node) {
        val children = node.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) {
                found += child
                walk(child)
            }
        }
    }
    walk(this)
    return found
}

private fun List<Element>.firstText(local: String): String =
    firstOrNull { it.localName.equals(local, ignoreCase = true) }?.textContent?.trim().orEmpty()

private fun Element.attr(name: String): String {
    if (hasAttribute(name)) return getAttribute(name)
    val attributes = attributes
    for (index in 0 until attributes.length) {
        val item = attributes.item(index)
        if (item.localName.equals(name, ignoreCase = true) || item.nodeName.equals(name, ignoreCase = true)) {
            return item.nodeValue.orEmpty()
        }
    }
    return ""
}
