package com.numbear.manjuan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryWalkTest {
    @Test
    fun localPlanKeepsMobiFamilyAndImageFolders() {
        val mixed = listOf(
            "page.jpg" to false,
            "story.mobi" to false,
            "story.azw" to false,
            "story.azw3" to false,
            "nested" to true,
            "__MACOSX" to true,
            ".hidden.epub" to false,
        )
        val plan = FolderImport.plan(mixed, depth = 0, nameOf = { it.first }, isDirectory = { it.second })
        assertEquals(listOf("story.mobi", "story.azw", "story.azw3"), plan.books.map { it.first })
        assertFalse(plan.imageFolder)
        assertEquals(listOf("nested"), plan.directories.map { it.first })

        val images = listOf(
            "01.jpg" to false,
            "02.png" to false,
            "note.nfo" to false,
            "extra" to true,
            ".DS_Store" to false,
            "Thumbs.db" to false,
        )
        val comic = FolderImport.plan(images, depth = 0, nameOf = { it.first }, isDirectory = { it.second })
        assertTrue(comic.books.isEmpty())
        assertTrue(comic.imageFolder)
        assertEquals(listOf("extra"), comic.directories.map { it.first })
    }

    @Test
    fun localPlanStopsAtDepthLimit() {
        val children = listOf("book.epub" to false, "more" to true)
        val capped = FolderImport.plan(
            children,
            depth = FolderImport.LOCAL_MAX_DEPTH,
            nameOf = { it.first },
            isDirectory = { it.second },
        )
        assertEquals(listOf("book.epub"), capped.books.map { it.first })
        assertTrue(capped.directories.isEmpty())
    }

    @Test
    fun webDavScanRecursesBooksAndSkipsJunk() {
        val tree = mapOf(
            "" to listOf(
                entry("novels/", "novels", directory = true),
                entry("root.epub", "root.epub"),
                entry("__MACOSX/", "__MACOSX", directory = true),
                entry(".hidden/", ".hidden", directory = true),
                entry(".secret.mobi", ".secret.mobi"),
                entry("notes.txt", "notes.txt"),
            ),
            "novels/" to listOf(
                entry("novels/a.mobi", "a.mobi"),
                entry("novels/b.azw", "b.azw"),
                entry("novels/c.azw3", "c.azw3"),
                entry("novels/deep/", "deep", directory = true),
                entry("novels/readme.md", "readme.md"),
            ),
            "novels/deep/" to listOf(
                entry("novels/deep/d2/", "d2", directory = true),
            ),
            "novels/deep/d2/" to listOf(
                entry("novels/deep/d2/too.mobi", "too.mobi"),
            ),
            "__MACOSX/" to listOf(entry("__MACOSX/junk.epub", "junk.epub")),
            ".hidden/" to listOf(entry(".hidden/no.epub", "no.epub")),
        )
        val found = ArrayList<String>()
        WebDavScan.forEachBook(startPath = "", maxDepth = 20, list = { path -> tree[path].orEmpty() }) {
            found += it.name
        }
        assertEquals(listOf("a.mobi", "b.azw", "c.azw3", "too.mobi", "root.epub", "notes.txt"), found)
    }

    @Test
    fun webDavScanHonorsDepthFromCurrentPath() {
        val tree = mapOf(
            "shelf/" to listOf(
                entry("shelf/here.epub", "here.epub"),
                entry("shelf/sub/", "sub", directory = true),
            ),
            "shelf/sub/" to listOf(
                entry("shelf/sub/inner.mobi", "inner.mobi"),
                entry("shelf/sub/deeper/", "deeper", directory = true),
            ),
            "shelf/sub/deeper/" to listOf(
                entry("shelf/sub/deeper/buried.azw3", "buried.azw3"),
            ),
        )
        val shallow = ArrayList<String>()
        WebDavScan.forEachBook(startPath = "shelf/", maxDepth = 1, list = { path -> tree[path].orEmpty() }) {
            shallow += it.name
        }
        assertEquals(listOf("here.epub", "inner.mobi"), shallow)

        val deep = ArrayList<String>()
        WebDavScan.forEachBook(startPath = "shelf/", maxDepth = 20, list = { path -> tree[path].orEmpty() }) {
            deep += it.name
        }
        assertEquals(listOf("here.epub", "inner.mobi", "buried.azw3"), deep)
    }

    private fun entry(path: String, name: String, directory: Boolean = false) =
        WebDavEntry(path = path, name = name, directory = directory, size = 12)
}
