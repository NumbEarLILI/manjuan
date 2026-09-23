package com.numbear.manjuan.core

object FormatDetector {
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    val bookExtensions = setOf(
        "txt", "epub", "mobi", "azw", "azw3", "pdf", "cbz", "cbr", "zip",
    )

    fun detectFile(
        name: String,
        header: ByteArray,
        zipPaths: List<String>? = null,
        mimetype: String? = null,
    ): Detection {
        val ext = extension(name)
        val zip = isZip(header)
        val pdf = isPdf(header)
        val rar = isRar(header)
        val mobi = isMobi(header)
        val epubMime = mimetype?.trim()?.startsWith("application/epub+zip") == true
        val imageNames = zipPaths.orEmpty().filter { isImageName(it) }

        if (epubMime && zip) {
            return Detection(BookFormat.EPUB)
        }

        return when (ext) {
            "txt" -> when {
                zip || pdf || rar || mobi -> Detection(
                    BookFormat.UNSUPPORTED,
                    "扩展名是 TXT，但文件内容不是纯文本",
                )
                else -> Detection(BookFormat.TXT)
            }
            "epub" -> when {
                zip -> Detection(BookFormat.EPUB)
                else -> Detection(BookFormat.UNSUPPORTED, "这不是有效的 EPUB（缺少 ZIP 文件头）")
            }
            "mobi", "azw" -> mobiOrError(mobi, "MOBI")
            "azw3" -> mobiOrError(mobi, "AZW3").let { found ->
                if (found.format == BookFormat.MOBI) Detection(BookFormat.AZW3) else found
            }
            "pdf" -> when {
                pdf -> Detection(BookFormat.PDF)
                else -> Detection(BookFormat.UNSUPPORTED, "这不是有效的 PDF（缺少 %PDF 文件头）")
            }
            "cbz" -> when {
                !zip -> Detection(BookFormat.UNSUPPORTED, "这不是有效的 CBZ（缺少 ZIP 文件头）")
                zipPaths != null && imageNames.isEmpty() -> Detection(
                    BookFormat.UNSUPPORTED,
                    "CBZ 里没有可显示的图片",
                )
                else -> Detection(BookFormat.CBZ)
            }
            "cbr" -> when {
                rar -> Detection(BookFormat.CBR)
                else -> Detection(BookFormat.UNSUPPORTED, "这不是有效的 CBR（缺少 RAR 文件头）")
            }
            "zip" -> when {
                !zip -> Detection(BookFormat.UNSUPPORTED, "这不是有效的 ZIP（缺少 ZIP 文件头）")
                zipPaths == null || imageNames.isNotEmpty() -> Detection(BookFormat.ZIP_IMAGES)
                else -> Detection(BookFormat.UNSUPPORTED, "压缩包里没有可阅读的图片或 EPUB")
            }
            else -> when {
                pdf -> Detection(BookFormat.PDF)
                mobi -> Detection(BookFormat.MOBI)
                zip && imageNames.isNotEmpty() -> Detection(BookFormat.ZIP_IMAGES)
                zip && epubMime -> Detection(BookFormat.EPUB)
                rar -> Detection(BookFormat.CBR)
                ext.isEmpty() && looksLikeText(header) -> Detection(BookFormat.TXT)
                else -> Detection(BookFormat.UNSUPPORTED, "不支持的格式：${name.ifBlank { "未命名文件" }}")
            }
        }
    }

    fun detectDirectory(name: String, childNames: List<String>): Detection {
        val images = childNames.filter { isImageName(it) }
        return if (images.isNotEmpty()) {
            Detection(BookFormat.IMAGE_FOLDER)
        } else {
            Detection(BookFormat.UNSUPPORTED, "文件夹「$name」里没有图片")
        }
    }

    fun isImageName(path: String): Boolean {
        val base = path.substringAfterLast('/').substringAfterLast('\\')
        if (base.startsWith(".") || base.equals("thumbs.db", ignoreCase = true)) return false
        if (path.contains("__MACOSX")) return false
        return extension(base) in imageExtensions
    }

    fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase().substringBefore('?')

    private fun mobiOrError(ok: Boolean, label: String): Detection =
        if (ok) Detection(BookFormat.MOBI) else Detection(
            BookFormat.UNSUPPORTED,
            "这不是有效的 $label（缺少 BOOKMOBI 标识）",
        )

    fun isZip(header: ByteArray): Boolean =
        header.size >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())

    fun isPdf(header: ByteArray): Boolean =
        header.size >= 5 &&
            header[0] == '%'.code.toByte() &&
            header[1] == 'P'.code.toByte() &&
            header[2] == 'D'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[4] == '-'.code.toByte()

    fun isRar(header: ByteArray): Boolean =
        header.size >= 7 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'a'.code.toByte() &&
            header[2] == 'r'.code.toByte() &&
            header[3] == '!'.code.toByte() &&
            header[4] == 0x1A.toByte() &&
            header[5] == 0x07.toByte()

    fun isMobi(header: ByteArray): Boolean {
        if (header.size < 68) return false
        return header.copyOfRange(60, 68).toString(Charsets.ISO_8859_1) == "BOOKMOBI"
    }

    private fun looksLikeText(header: ByteArray): Boolean {
        if (header.isEmpty()) return true
        val sample = header.take(64)
        val controls = sample.count { b ->
            val v = b.toInt() and 0xFF
            v != 9 && v != 10 && v != 13 && v < 32
        }
        return controls * 4 < sample.size
    }
}
