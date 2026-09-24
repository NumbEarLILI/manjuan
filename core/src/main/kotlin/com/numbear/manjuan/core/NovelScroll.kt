package com.numbear.manjuan.core

/**
 * Pieces of a chapter small enough to scroll through.
 *
 * One text block for a whole chapter is taller than Compose can place: constraints top out
 * around 256K px, and the scrollable is drawn as a single layer. Past that, the chapter stops
 * moving. Callers pick [maxChars] from the real line height so each block stays under that cap.
 */
object NovelScroll {
    /** Below the common 4096px graphics-layer limit, with room for font padding. */
    const val MaxBlockPx = 3072

    sealed class Block {
        abstract val start: Int

        data class Words(override val start: Int, val text: String) : Block()

        data class Picture(override val start: Int, val bytes: ByteArray) : Block() {
            override fun equals(other: Any?): Boolean =
                other is Picture && start == other.start && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = 31 * start + bytes.contentHashCode()
        }
    }

    fun maxChars(charsPerLine: Int, lineHeightPx: Float, maxBlockPx: Int = MaxBlockPx): Int {
        val lines = (maxBlockPx.toFloat() / lineHeightPx.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        return (charsPerLine.coerceAtLeast(1) * lines).coerceAtLeast(1)
    }

    fun blocks(chapter: NovelChapter, maxChars: Int): List<Block> {
        val limit = maxChars.coerceAtLeast(1)
        val spans = chapter.spans.ifEmpty { listOf(NovelSpan.Prose(chapter.text)) }
        if (spans.none { it is NovelSpan.Plate }) {
            val joined = spans.filterIsInstance<NovelSpan.Prose>().joinToString("\n") { it.text }
            val source = when {
                chapter.spans.isEmpty() -> chapter.text
                joined == chapter.text || chapter.text.isEmpty() -> joined
                else -> chapter.text
            }
            return chunk(source, 0, limit)
        }
        val blocks = ArrayList<Block>()
        var from = 0
        for (span in spans) {
            when (span) {
                is NovelSpan.Plate -> blocks += Block.Picture(from, span.bytes)
                is NovelSpan.Prose -> {
                    if (span.text.isEmpty()) continue
                    val at = chapter.text.indexOf(span.text, from.coerceAtMost(chapter.text.length))
                    val start = if (at >= 0) at else from
                    blocks += chunk(span.text, start, limit)
                    from = start + span.text.length
                }
            }
        }
        return blocks
    }

    fun indexAt(blocks: List<Block>, offset: Int): Int {
        if (blocks.isEmpty()) return 0
        val target = offset.coerceAtLeast(0)
        var index = 0
        for (i in blocks.indices) {
            if (blocks[i].start <= target) index = i else break
        }
        return index
    }

    /**
     * One scrollable sequence for the whole book. A chapter ends, and the next chapter follows,
     * so a drag that reaches the bottom continues into the next chapter.
     */
    sealed class Entry {
        abstract val chapter: Int

        data class Heading(override val chapter: Int, val title: String) : Entry()

        data class Body(override val chapter: Int, val block: Block) : Entry()
    }

    fun document(chapters: List<NovelChapter>, maxChars: Int): List<Entry> {
        val entries = ArrayList<Entry>()
        chapters.forEachIndexed { index, chapter ->
            if (needsHeading(chapter)) entries += Entry.Heading(index, chapter.title.trim())
            for (block in blocks(chapter, maxChars)) {
                entries += Entry.Body(index, block)
            }
        }
        return entries
    }

    fun indexAt(entries: List<Entry>, chapter: Int, offset: Int): Int {
        if (entries.isEmpty()) return 0
        val targetChapter = chapter.coerceAtLeast(0)
        val targetOffset = offset.coerceAtLeast(0)
        val owned = entries.indices.filter { entries[it].chapter == targetChapter }
        if (owned.isEmpty()) {
            val earlier = entries.indexOfLast { it.chapter < targetChapter }
            return if (earlier >= 0) earlier else 0
        }
        if (targetOffset == 0) return owned.first()
        var index = owned.first()
        for (i in owned) {
            val body = entries[i] as? Entry.Body ?: continue
            if (body.block.start <= targetOffset) index = i
        }
        return index
    }

    private fun needsHeading(chapter: NovelChapter): Boolean {
        val title = chapter.title.trim()
        if (title.isEmpty()) return false
        val first = chapter.text.trimStart().lineSequence().firstOrNull()?.trim().orEmpty()
        return first != title && !first.startsWith(title)
    }

    private fun chunk(text: String, base: Int, limit: Int): List<Block.Words> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Block.Words>()
        var index = 0
        while (index < text.length) {
            val end = breakAt(text, index, limit)
            if (end <= index) break
            out += Block.Words(base + index, text.substring(index, end))
            index = end
        }
        return out
    }

    /** Keep a newline inside the chunk when one sits in the latter half of the window. */
    private fun breakAt(text: String, start: Int, limit: Int): Int {
        val hard = minOf(start + limit, text.length)
        if (hard >= text.length) return text.length
        val earliest = start + (limit / 2).coerceAtLeast(1)
        val newline = text.lastIndexOf('\n', hard - 1)
        if (newline >= earliest) return newline + 1
        return hard
    }
}
