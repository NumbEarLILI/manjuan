package com.numbear.manjuan.core

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class RemoteBytes(val bytes: ByteArray, val total: Long)

internal fun contentRangeTotal(header: String?): Long {
    val total = header?.substringAfter('/', "")?.trim().orEmpty()
    if (total.isEmpty() || total == "*") return -1L
    return total.toLongOrNull() ?: -1L
}

class OkHttpWebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient = defaultClient(),
) {
    val baseUrl: String = normalizeBase(baseUrl)

    fun test(path: String = ""): WebDavStatus {
        val url = resolve(path)
            ?: return WebDavStatus.Failed("请填写以 http:// 或 https:// 开头的地址")
        return try {
            execute(propfind(url)) { response ->
                when (response.code) {
                    200, 207 -> WebDavStatus.Ok
                    401, 403 -> WebDavStatus.Failed("账号或密码不正确")
                    else -> WebDavStatus.Failed("服务器返回 HTTP ${response.code}")
                }
            }
        } catch (error: UnsupportedBookException) {
            WebDavStatus.Failed(error.message ?: "无法连接服务器")
        }
    }

    fun list(path: String): List<WebDavEntry> {
        val url = resolve(path) ?: throw UnsupportedBookException("WebDAV 地址无效")
        val requestPath = url.toHttpUrlOrNull()?.encodedPath?.let { encoded ->
            URLDecoder.decode(encoded, Charsets.UTF_8.name())
        } ?: path.ifBlank { "/" }
        val request = propfind(url)
        return execute(request) { response ->
            when (response.code) {
                401, 403 -> throw UnsupportedBookException("账号或密码不正确")
                200, 207 -> WebDavXml.parse(response.body?.string().orEmpty(), requestPath)
                else -> throw UnsupportedBookException("服务器返回 HTTP ${response.code}")
            }
        }
    }

    fun download(path: String, dest: File, onProgress: (read: Long, total: Long) -> Unit = { _, _ -> }) {
        val url = resolve(path) ?: throw UnsupportedBookException("WebDAV 地址无效")
        val request = Request.Builder().url(url).header("Authorization", authorization()).get().build()
        val partial = File(dest.parentFile, dest.name + ".part")
        try {
            execute(request, "无法下载") { response ->
                when (response.code) {
                    401, 403 -> throw UnsupportedBookException("账号或密码不正确")
                    in 200..299 -> {
                        val body = response.body ?: throw UnsupportedBookException("无法下载")
                        partial.parentFile?.mkdirs()
                        val total = body.contentLength()
                        onProgress(0L, total)
                        var readTotal = 0L
                        body.byteStream().use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var lastReport = 0L
                                while (true) {
                                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已取消")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) throw UnsupportedBookException("无法下载")
                                    output.write(buffer, 0, count)
                                    readTotal += count
                                    val now = System.currentTimeMillis()
                                    if (total in 0..readTotal || now - lastReport >= 100) {
                                        lastReport = now
                                        onProgress(readTotal, total)
                                    }
                                }
                            }
                        }
                        if (partial.length() == 0L) throw UnsupportedBookException("无法下载")
                        if (total >= 0 && readTotal != total) throw UnsupportedBookException("下载不完整")
                        if (dest.exists() && !dest.delete()) throw UnsupportedBookException("无法下载")
                        if (!partial.renameTo(dest)) {
                            partial.copyTo(dest, overwrite = true)
                            partial.delete()
                        }
                        onProgress(dest.length(), dest.length())
                    }
                    else -> throw UnsupportedBookException("无法下载（HTTP ${response.code}）")
                }
            }
        } catch (error: UnsupportedBookException) {
            partial.delete()
            throw error
        } catch (error: InterruptedIOException) {
            partial.delete()
            throw error
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    /**
     * Reads at most [length] bytes starting at [start]. A 206 response is that slice.
     * A 200 response on the first byte is truncated to [length] and the rest of the body is cancelled.
     */
    fun readRange(path: String, start: Long, length: Int): RemoteBytes {
        if (length <= 0) return RemoteBytes(ByteArray(0), -1)
        val url = resolve(path) ?: throw UnsupportedBookException("WebDAV 地址无效")
        val end = start + length - 1
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization())
            .header("Range", "bytes=$start-$end")
            .get()
            .build()
        val call = client.newCall(request)
        var result: RemoteBytes? = null
        try {
            call.execute().use { response ->
                result = when (response.code) {
                    401, 403 -> throw UnsupportedBookException("账号或密码不正确")
                    206 -> {
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        val limited = if (bytes.size > length) bytes.copyOf(length) else bytes
                        RemoteBytes(limited, contentRangeTotal(response.header("Content-Range")))
                    }
                    200 -> {
                        if (start > 0) throw UnsupportedBookException("服务器不支持分段下载")
                        val body = response.body ?: throw UnsupportedBookException("无法下载")
                        val total = body.contentLength()
                        val bytes = readAtMost(body.byteStream(), length)
                        call.cancel()
                        RemoteBytes(bytes, total)
                    }
                    416 -> RemoteBytes(ByteArray(0), contentRangeTotal(response.header("Content-Range")))
                    else -> throw UnsupportedBookException("无法下载（HTTP ${response.code}）")
                }
            }
        } catch (error: UnsupportedBookException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw UnsupportedBookException("网络超时")
        } catch (error: InterruptedIOException) {
            if (result != null) return result
            if (Thread.currentThread().isInterrupted) throw error
            throw UnsupportedBookException("网络超时")
        } catch (error: Exception) {
            if (result != null) return result
            throw UnsupportedBookException("无法下载")
        }
        return result ?: throw UnsupportedBookException("无法下载")
    }

    fun peek(path: String, count: Int = 128): ByteArray {
        val url = resolve(path) ?: return ByteArray(0)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization())
            .header("Range", "bytes=0-${count - 1}")
            .get()
            .build()
        return try {
            execute(request, "无法下载") { response ->
                if (response.code == 401 || response.code == 403) {
                    throw UnsupportedBookException("账号或密码不正确")
                }
                val bytes = response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(count)
                    val read = input.read(buffer)
                    if (read <= 0) ByteArray(0) else buffer.copyOf(read)
                } ?: ByteArray(0)
                bytes
            }
        } catch (error: InterruptedIOException) {
            throw error
        } catch (error: UnsupportedBookException) {
            throw error
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun propfind(url: String): Request {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        return Request.Builder()
            .url(url)
            .header("Authorization", authorization())
            .header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
            .build()
    }

    private fun authorization(): String = Credentials.basic(username, password, Charsets.UTF_8)

    private fun resolve(path: String): String? = WebDavPaths.resolveUrl(baseUrl, path)

    private fun <T> execute(request: Request, block: (okhttp3.Response) -> T): T =
        execute(request, "无法连接服务器", block)

    private fun <T> execute(request: Request, ioMessage: String, block: (okhttp3.Response) -> T): T {
        try {
            client.newCall(request).execute().use { response -> return block(response) }
        } catch (error: UnsupportedBookException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw UnsupportedBookException("网络超时")
        } catch (error: InterruptedIOException) {
            if (Thread.currentThread().isInterrupted) throw error
            throw UnsupportedBookException("网络超时")
        } catch (_: Exception) {
            throw UnsupportedBookException(ioMessage)
        }
    }

    private fun readAtMost(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已取消")
            val count = input.read(out, offset, length - offset)
            if (count < 0) break
            if (count == 0) throw UnsupportedBookException("无法下载")
            offset += count
        }
        return if (offset == length) out else out.copyOf(offset)
    }

    companion object {
        fun normalizeBase(url: String): String {
            val trimmed = url.trim()
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else trimmed
            return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
