package com.numbear.manjuan.core

import java.io.File

object BookCache {
    const val DIR = "cache-books"

    fun size(root: File): Long {
        if (!root.exists()) return 0L
        var total = 0L
        root.walkTopDown().forEach { file ->
            if (file.isFile) total += file.length()
        }
        return total
    }

    fun storedInside(cacheRoot: String, path: String): Boolean {
        if (cacheRoot.isBlank() || path.isBlank()) return false
        val root = normalize(cacheRoot)
        val target = normalize(path)
        return target == root || target.startsWith("$root/")
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')
}
