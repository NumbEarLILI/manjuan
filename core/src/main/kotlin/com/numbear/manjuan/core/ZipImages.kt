package com.numbear.manjuan.core

import java.io.File
import java.util.zip.ZipFile

object ZipImages {
    fun list(file: File): List<String> {
        ZipFile(file, Charsets.UTF_8).use { zip ->
            val names = zip.entries().toList().map { it.name }
            val images = names.filter { FormatDetector.isImageName(it) }
            if (images.isNotEmpty()) return images.sortedWith(NaturalSort)
        }
        ZipFile(file, charset("GB18030")).use { zip ->
            return zip.entries().toList().map { it.name }
                .filter { FormatDetector.isImageName(it) }
                .sortedWith(NaturalSort)
        }
    }

    fun readMimetype(file: File): String? = try {
        ZipFile(file, Charsets.UTF_8).use { zip ->
            val entry = zip.getEntry("mimetype") ?: return null
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.US_ASCII).trim() }
        }
    } catch (_: Exception) {
        null
    }
}
