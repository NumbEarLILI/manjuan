package com.numbear.manjuan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookCacheTest {
    @Test
    fun sizeSumsNestedFilesAndTreatsMissingRootAsEmpty() {
        val root = tempRoot()
        try {
            assertEquals(0L, BookCache.size(root))
            File(root, "1").mkdirs()
            File(root, "1/book.txt").writeBytes(ByteArray(100))
            File(root, "1/images").mkdirs()
            File(root, "1/images/page.jpg").writeBytes(ByteArray(40))
            assertEquals(140L, BookCache.size(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storedInsideMatchesTheCacheTreeOnly() {
        val root = "/data/user/0/com.numbear.manjuan/files/${BookCache.DIR}"
        assertTrue(BookCache.storedInside(root, "$root/3/book.txt"))
        assertTrue(BookCache.storedInside("$root/", root))
        assertFalse(BookCache.storedInside(root, ""))
        assertFalse(BookCache.storedInside(root, "$root-extra/book.txt"))
        assertFalse(BookCache.storedInside(root, "/data/user/0/com.numbear.manjuan/files/local/novel.txt"))
    }

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "book-cache-${System.nanoTime()}").apply { mkdirs() }
}
