package com.numbear.manjuan.data.open

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.numbear.manjuan.core.UnsupportedBookException
import java.io.File

object BitmapIO {
    fun decodeFile(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw UnsupportedBookException("无法解码图片：${file.name}")
    }

    fun decodeUri(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw UnsupportedBookException("无法读取图片")
    }

    fun pdfPageCount(file: File): Int = withRenderer(file) { it.pageCount }

    fun renderPdf(file: File, index: Int, maxSide: Int): Bitmap = withRenderer(file) { renderer ->
        renderer.openPage(index).use { page ->
            val longest = maxOf(page.width, page.height).coerceAtLeast(1)
            val scale = maxSide.toFloat() / longest
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.WHITE)
            page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    private fun <T> withRenderer(file: File, block: (PdfRenderer) -> T): T {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        try {
            return block(renderer)
        } finally {
            renderer.close()
            descriptor.close()
        }
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > maxSide * 1.4) sample *= 2
        return sample.coerceAtLeast(1)
    }
}
