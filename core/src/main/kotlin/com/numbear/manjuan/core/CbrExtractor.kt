package com.numbear.manjuan.core

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarV5Exception
import java.io.File
import java.io.FileOutputStream

object CbrExtractor {
    fun extractImages(rar: File, destDir: File): List<File> {
        destDir.mkdirs()
        try {
            Archive(rar).use { archive ->
                val written = ArrayList<File>()
                for (header in archive) {
                    if (header.isDirectory) continue
                    val name = header.fileName.ifBlank { "page" }
                    if (!FormatDetector.isImageName(name)) continue
                    val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "page-${written.size}.img" }
                    val out = unique(destDir, safe)
                    FileOutputStream(out).use { stream -> archive.extractFile(header, stream) }
                    written += out
                }
                if (written.isEmpty()) throw UnsupportedBookException("CBR 里没有可显示的图片")
                return written.sortedWith(compareBy(NaturalSort) { it.name })
            }
        } catch (error: UnsupportedBookException) {
            throw error
        } catch (_: UnsupportedRarV5Exception) {
            throw UnsupportedBookException("此 CBR 使用 RAR5，当前版本暂不支持")
        } catch (error: RarException) {
            throw UnsupportedBookException("无法解压 CBR：${error.message ?: "RAR 读取失败"}")
        } catch (error: Exception) {
            throw UnsupportedBookException("无法解压 CBR：${error.message ?: "未知错误"}")
        }
    }

    private fun unique(dir: File, name: String): File {
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            val dot = name.lastIndexOf('.')
            val next = if (dot > 0) name.substring(0, dot) + "-$index" + name.substring(dot) else "$name-$index"
            candidate = File(dir, next)
            index++
        }
        return candidate
    }
}
