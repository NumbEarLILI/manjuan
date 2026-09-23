package com.numbear.manjuan.core

/**
 * WebDAV listing names and on-disk bytes do not always agree with the format stored at import.
 * Comics must stay on the comic/PDF path: opening a CBZ/CBR/ZIP as TXT renders the archive as 乱码.
 */
object WebDavBooks {
    fun displayFileName(displayName: String, path: String): String {
        val fromPath = path.trim().substringBefore('?').trimEnd('/').substringAfterLast('/')
        val fromName = displayName.trim().substringAfterLast('/').substringAfterLast('\\')
        val pathExt = FormatDetector.extension(fromPath)
        val nameExt = FormatDetector.extension(fromName)
        val pathKnown = pathExt in FormatDetector.bookExtensions
        val nameKnown = nameExt in FormatDetector.bookExtensions
        return when {
            pathKnown && !nameKnown -> fromPath
            nameKnown -> fromName
            fromPath.isNotBlank() -> fromPath
            else -> fromName
        }
    }

    fun isImportableFile(displayName: String, path: String): Boolean {
        val name = displayFileName(displayName, path)
        return LibraryNames.isBookFile(name) || LibraryNames.isBookFile(displayName) || LibraryNames.isBookFile(path)
    }

    fun classify(displayName: String, path: String, directory: Boolean, header: ByteArray = ByteArray(0)): Detection {
        if (directory) return Detection(BookFormat.IMAGE_FOLDER)
        val fileName = displayFileName(displayName, path)
        val extension = FormatDetector.extension(fileName)
        if (FormatDetector.isZip(header) && extension != "epub") {
            return Detection(if (extension == "cbz") BookFormat.CBZ else BookFormat.ZIP_IMAGES)
        }
        if (FormatDetector.isRar(header)) return Detection(BookFormat.CBR)
        if (FormatDetector.isPdf(header)) return Detection(BookFormat.PDF)
        if (header.isEmpty()) {
            when (extension) {
                "cbz" -> return Detection(BookFormat.CBZ)
                "cbr" -> return Detection(BookFormat.CBR)
                "zip" -> return Detection(BookFormat.ZIP_IMAGES)
                "pdf" -> return Detection(BookFormat.PDF)
            }
        }
        return FormatDetector.detectFile(fileName, header)
    }

    fun resolve(
        formatName: String,
        kindName: String,
        location: String,
        remotePath: String,
        title: String,
        header: ByteArray = ByteArray(0),
        cachedImageCount: Int = 0,
    ): Detection {
        if (cachedImageCount > 0) return Detection(BookFormat.IMAGE_FOLDER)
        if (location.startsWith("saf:")) return Detection(BookFormat.IMAGE_FOLDER)
        val stored = runCatching { BookFormat.valueOf(formatName) }.getOrDefault(BookFormat.UNSUPPORTED)
        if (stored == BookFormat.CBZ || stored == BookFormat.CBR || stored == BookFormat.ZIP_IMAGES || stored == BookFormat.IMAGE_FOLDER) {
            return Detection(stored)
        }
        val classified = classify(title, remotePath.ifBlank { location }, directory = false, header)
        if (classified.format.kind() == BookKind.COMIC) return classified
        if (stored == BookFormat.PDF || classified.format == BookFormat.PDF) return Detection(BookFormat.PDF)
        if (classified.format == BookFormat.MOBI || classified.format == BookFormat.AZW3) return classified
        if (stored.kind() == BookKind.NOVEL) return Detection(stored)
        if (kindName.equals("COMIC", ignoreCase = true)) {
            return Detection(if (stored.kind() == BookKind.COMIC) stored else BookFormat.ZIP_IMAGES)
        }
        if (kindName.equals("PDF", ignoreCase = true)) return Detection(BookFormat.PDF)
        return if (stored != BookFormat.UNSUPPORTED) Detection(stored) else classified
    }
}
