package com.numbear.manjuan.core

/**
 * Shared rules for recursive library import.
 * Local SAF walks use [FolderImport]. WebDAV 「扫描导入」 uses [WebDavScan].
 */
object LibraryNames {
    fun isJunk(value: String): Boolean {
        val segments = value.split('/', '\\').filter { it.isNotBlank() }
        if (segments.isEmpty()) return false
        return segments.any { segment ->
            segment.startsWith('.') ||
                segment.equals("__MACOSX", ignoreCase = true) ||
                segment.equals("Thumbs.db", ignoreCase = true) ||
                segment.equals("desktop.ini", ignoreCase = true)
        }
    }

    fun isBookFile(name: String): Boolean {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return FormatDetector.extension(base) in FormatDetector.bookExtensions
    }
}

data class FolderPlan<T>(
    val books: List<T>,
    val imageFolder: Boolean,
    val directories: List<T>,
)

object FolderImport {
    const val LOCAL_MAX_DEPTH = 32

    /**
     * Root is depth 0. Subfolders are included only while [depth] is below [maxDepth].
     * A folder with book files is not also treated as an image comic.
     * A folder with no books and at least one image is an image comic, and its subfolders are still walked.
     */
    fun <T> plan(
        children: List<T>,
        depth: Int,
        maxDepth: Int = LOCAL_MAX_DEPTH,
        nameOf: (T) -> String,
        isDirectory: (T) -> Boolean,
    ): FolderPlan<T> {
        val usable = children.filter { !LibraryNames.isJunk(nameOf(it)) }
        val files = usable.filter { !isDirectory(it) }
        val dirs = usable.filter { isDirectory(it) }
        val books = files.filter { LibraryNames.isBookFile(nameOf(it)) }
        val images = files.filter { FormatDetector.isImageName(nameOf(it)) }
        return FolderPlan(
            books = books,
            imageFolder = books.isEmpty() && images.isNotEmpty(),
            directories = if (depth < maxDepth) dirs else emptyList(),
        )
    }
}

object WebDavScan {
    const val MAX_DEPTH = 20

    /**
     * Depth-first listing starting at [startPath] (depth 0).
     * Visits book files and skips junk. Does not descend into a directory once [maxDepth] is reached.
     */
    fun forEachBook(
        startPath: String,
        maxDepth: Int = MAX_DEPTH,
        isActive: () -> Boolean = { true },
        list: (String) -> List<WebDavEntry>,
        onDirectory: (String) -> Unit = {},
        onBook: (WebDavEntry) -> Unit,
    ) {
        val visited = HashSet<String>()
        fun walk(path: String, depth: Int) {
            if (!isActive()) return
            val key = path.trim().trimEnd('/').ifBlank { "/" }
            if (!visited.add(key)) return
            onDirectory(path)
            val children = list(path)
            for (entry in children) {
                if (!isActive()) return
                if (LibraryNames.isJunk(entry.name) || LibraryNames.isJunk(entry.path)) continue
                if (entry.directory) {
                    if (depth < maxDepth) walk(entry.path, depth + 1)
                } else if (LibraryNames.isBookFile(entry.name)) {
                    onBook(entry)
                }
            }
        }
        walk(startPath, 0)
    }
}
