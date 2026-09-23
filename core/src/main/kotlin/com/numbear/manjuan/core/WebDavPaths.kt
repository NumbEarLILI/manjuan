package com.numbear.manjuan.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

object WebDavPaths {
    fun joinBase(url: String, root: String): String {
        val base = OkHttpWebDavClient.normalizeBase(url)
        val http = base.toHttpUrlOrNull() ?: return base
        val extra = root.trim().replace('\\', '/').trim('/')
        if (extra.isEmpty()) return base
        val baseDecoded = decodeSegments(http.encodedPath)
        if (extra == baseDecoded || (baseDecoded.isNotEmpty() && baseDecoded.endsWith("/$extra"))) return base
        val suffix = if (baseDecoded.isNotEmpty() && extra.startsWith("$baseDecoded/")) {
            extra.removePrefix("$baseDecoded/")
        } else {
            extra
        }
        if (suffix.isEmpty()) return base
        return resolveUrl(base, suffix.trim('/') + "/") ?: base
    }

    fun hrefToPath(href: String, requestPath: String): String {
        val decoded = decode(href.substringBefore('?').trim())
        val absolute = when {
            decoded.startsWith("http://") || decoded.startsWith("https://") -> {
                val rest = decoded.substringAfter("://")
                val slash = rest.indexOf('/')
                if (slash < 0) "/" else rest.substring(slash)
            }
            decoded.startsWith("/") -> decoded
            else -> collectionDir(requestPath) + decoded
        }
        return collapse(absolute)
    }

    fun resolveUrl(baseUrl: String, path: String): String? {
        val base = OkHttpWebDavClient.normalizeBase(baseUrl).toHttpUrlOrNull() ?: return null
        val relative = path.trim()
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative.toHttpUrlOrNull()?.toString()
        }
        if (relative.isEmpty() || relative == "/") return base.toString()
        val segments = ArrayDeque<String>()
        if (!relative.startsWith("/")) {
            decodeSegments(base.encodedPath).split('/').filter { it.isNotEmpty() }.forEach { segments.add(it) }
        }
        relative.trim('/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.add(part)
            }
        }
        val builder = base.newBuilder().query(null).fragment(null)
        builder.encodedPath("/")
        segments.forEach { builder.addPathSegment(it) }
        val built = builder.build()
        if (relative.endsWith("/") && !built.encodedPath.endsWith("/")) {
            val slashed = built.encodedPath.trimEnd('/') + "/"
            return built.newBuilder().encodedPath(slashed).build().toString()
        }
        return built.toString()
    }

    /**
     * A WebDAV location often looks like `/dav/book.cbz`. That string is a server path.
     * Only a cached local file, or a non-WebDAV filesystem path, may be opened with [java.io.File].
     */
    fun localReadablePath(
        sourceType: String,
        location: String,
        cachedPath: String,
        remotePath: String,
        isRegularFile: (String) -> Boolean,
    ): String? {
        if (cachedPath.isNotBlank() && isRegularFile(cachedPath)) return cachedPath
        val remote = sourceType.equals("WEBDAV", ignoreCase = true) || remotePath.isNotBlank()
        if (remote || location.startsWith("saf:")) return null
        if (location.startsWith("/") && isRegularFile(location)) return location
        return null
    }

    private fun collectionDir(requestPath: String): String {
        val trimmed = requestPath.trim().ifBlank { "/" }
        val absolute = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (absolute.endsWith("/")) absolute else "$absolute/"
    }

    private fun collapse(path: String): String {
        val absolute = path.trim().ifBlank { "/" }
        val slash = absolute.endsWith("/")
        val segments = ArrayDeque<String>()
        absolute.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.add(part)
            }
        }
        val joined = segments.joinToString("/")
        return when {
            joined.isEmpty() -> "/"
            slash -> "/$joined/"
            else -> "/$joined"
        }
    }

    private fun decodeSegments(encodedPath: String): String =
        encodedPath.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { decode(it) }

    private fun decode(raw: String): String = try {
        URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (_: Exception) {
        raw
    }
}
