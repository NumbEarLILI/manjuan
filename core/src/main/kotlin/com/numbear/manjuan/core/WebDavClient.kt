package com.numbear.manjuan.core

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

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
        execute(request) { response ->
            when (response.code) {
                401, 403 -> throw UnsupportedBookException("账号或密码不正确")
                in 200..299 -> {
                    val body = response.body ?: throw UnsupportedBookException("服务器没有返回文件内容")
                    dest.parentFile?.mkdirs()
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var readTotal = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                readTotal += count
                                onProgress(readTotal, total)
                            }
                        }
                    }
                }
                else -> throw UnsupportedBookException("下载失败，HTTP ${response.code}")
            }
        }
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
            execute(request) { response ->
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

    private fun resolve(path: String): String? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val relative = path.trim()
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        if (relative.isEmpty() || relative == "/") return base.toString()
        if (relative.startsWith("/")) {
            val origin = base.newBuilder().encodedPath("/").query(null).fragment(null).build()
            return origin.resolve(relative.trimStart('/'))?.toString()
        }
        return base.resolve(relative)?.toString()
    }

    private fun <T> execute(request: Request, block: (okhttp3.Response) -> T): T {
        try {
            client.newCall(request).execute().use { response -> return block(response) }
        } catch (error: UnsupportedBookException) {
            throw error
        } catch (_: Exception) {
            throw UnsupportedBookException("无法连接服务器")
        }
    }

    companion object {
        fun normalizeBase(url: String): String {
            val trimmed = url.trim()
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else trimmed
            return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
