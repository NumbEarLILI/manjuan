package com.numbear.manjuan.data.repo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.numbear.manjuan.core.BookFormat
import com.numbear.manjuan.core.BookKind
import com.numbear.manjuan.core.CbrExtractor
import com.numbear.manjuan.core.EpubParser
import com.numbear.manjuan.core.FormatDetector
import com.numbear.manjuan.core.MobiParser
import com.numbear.manjuan.core.NovelChapter
import com.numbear.manjuan.core.NovelContent
import com.numbear.manjuan.core.OkHttpWebDavClient
import com.numbear.manjuan.core.ProgressMerge
import com.numbear.manjuan.core.ReadingProgress
import com.numbear.manjuan.core.TextEncoding
import com.numbear.manjuan.core.TxtChapters
import com.numbear.manjuan.core.UnsupportedBookException
import com.numbear.manjuan.core.WebDavEntry
import com.numbear.manjuan.core.WebDavStatus
import com.numbear.manjuan.core.ZipImages
import com.numbear.manjuan.core.kind
import com.numbear.manjuan.data.crypto.WebDavCipher
import com.numbear.manjuan.data.db.BookEntity
import com.numbear.manjuan.data.db.BookmarkEntity
import com.numbear.manjuan.data.db.ManjuanDatabase
import com.numbear.manjuan.data.db.ProgressEntity
import com.numbear.manjuan.data.db.SourceEntity
import com.numbear.manjuan.data.open.BitmapIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class LibraryRepository(
    private val context: Context,
    private val database: ManjuanDatabase,
    private val cipher: WebDavCipher,
) {
    fun observeBooks(): Flow<List<BookEntity>> = database.books().observeAll()

    fun observeSources(): Flow<List<SourceEntity>> = database.sources().observeAll()

    fun observeProgress(): Flow<List<com.numbear.manjuan.data.db.ProgressEntity>> = database.progress().observeAll()

    suspend fun importUris(uris: List<Uri>): ImportReport = withContext(Dispatchers.IO) {
        val sourceId = ensureLocalSource()
        var added = 0
        val errors = ArrayList<String>()
        for (uri in uris) {
            val name = displayName(uri)
            try {
                importLocalFile(sourceId, name, uri)
                added++
            } catch (error: UnsupportedBookException) {
                errors += "$name：${error.message}"
            } catch (_: Exception) {
                errors += "$name：无法读取文件"
            }
        }
        ImportReport(added, errors)
    }

    suspend fun importTree(uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // The picker still grants a temporary permission for this call.
        }
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: return@withContext ImportReport(0, listOf("无法打开所选文件夹"))
        val sourceId = ensureLocalSource()
        val errors = ArrayList<String>()
        var added = 0
        suspend fun walk(doc: DocumentFile) {
            val children = doc.listFiles().toList()
            val files = children.filter { it.isFile }
            val dirs = children.filter { it.isDirectory }
            val books = files.filter { FormatDetector.extension(it.name ?: "") in FormatDetector.bookExtensions }
            val images = files.filter { FormatDetector.isImageName(it.name ?: "") }
            if (books.isNotEmpty()) {
                books.forEach { file ->
                    val name = file.name ?: "未命名"
                    try {
                        importLocalFile(sourceId, name, file.uri)
                        added++
                    } catch (error: UnsupportedBookException) {
                        errors += "$name：${error.message}"
                    } catch (_: Exception) {
                        errors += "$name：无法读取文件"
                    }
                }
                dirs.forEach { walk(it) }
            } else if (images.isNotEmpty() && dirs.isEmpty()) {
                try {
                    importImageFolder(sourceId, doc.name ?: "图片文件夹", uri, doc)
                    added++
                } catch (error: UnsupportedBookException) {
                    errors += "${doc.name}：${error.message}"
                }
            } else if (images.isNotEmpty()) {
                try {
                    importImageFolder(sourceId, doc.name ?: "图片文件夹", uri, doc)
                    added++
                } catch (error: UnsupportedBookException) {
                    errors += "${doc.name}：${error.message}"
                }
                dirs.forEach { walk(it) }
            } else {
                dirs.forEach { walk(it) }
            }
        }
        walk(root)
        if (added == 0 && errors.isEmpty()) {
            errors += "文件夹里没有支持的小说、漫画或图片"
        }
        ImportReport(added, errors)
    }

    suspend fun saveWebDav(
        name: String,
        url: String,
        username: String,
        password: String,
        root: String,
    ): Long = withContext(Dispatchers.IO) {
        val status = testWebDav(url, username, password, root)
        if (status is WebDavStatus.Failed) throw UnsupportedBookException(status.message)
        database.sources().insert(
            SourceEntity(
                type = WEBDAV,
                displayName = name.ifBlank { url.trim() },
                baseUrl = url.trim(),
                username = username,
                passwordCipher = cipher.encrypt(password),
                rootPath = root.trim(),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun testWebDav(url: String, username: String, password: String, root: String): WebDavStatus =
        withContext(Dispatchers.IO) {
            if (!url.trim().startsWith("http://") && !url.trim().startsWith("https://")) {
                return@withContext WebDavStatus.Failed("请填写以 http:// 或 https:// 开头的地址")
            }
            client(url, username, password, root).test()
        }

    suspend fun listWebDav(sourceId: Long, path: String): List<WebDavEntry> = withContext(Dispatchers.IO) {
        client(requireSource(sourceId)).list(path)
    }

    suspend fun addRemote(sourceId: Long, entry: WebDavEntry): Long = withContext(Dispatchers.IO) {
        val existing = database.books().findRemote(sourceId, entry.path)
        if (existing != null) return@withContext existing.id
        val format = if (entry.directory) {
            BookFormat.IMAGE_FOLDER
        } else {
            val header = client(requireSource(sourceId)).peek(entry.path)
            val detection = FormatDetector.detectFile(entry.name, header)
            if (detection.format == BookFormat.UNSUPPORTED) {
                throw UnsupportedBookException(detection.error ?: "不支持的格式：${entry.name}")
            }
            detection.format
        }
        val kind = format.kind() ?: throw UnsupportedBookException("不支持的格式：${entry.name}")
        database.books().insert(
            BookEntity(
                sourceId = sourceId,
                title = entry.name.substringBeforeLast('.').ifBlank { entry.name },
                format = format.name,
                kind = kind.name,
                location = entry.path,
                remotePath = entry.path,
                addedAt = System.currentTimeMillis(),
                sizeBytes = entry.size,
            ),
        )
    }

    suspend fun cacheBook(bookId: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val book = database.books().get(bookId) ?: throw UnsupportedBookException("找不到这本书")
        val source = database.sources().get(book.sourceId) ?: throw UnsupportedBookException("找不到来源")
        if (source.type != WEBDAV) {
            if (book.cachedPath.isBlank() && book.location.startsWith("/")) {
                database.books().update(book.copy(cachedPath = book.location))
            }
            return@withContext
        }
        val remote = client(source)
        val destDir = File(context.filesDir, "cache-books/${book.id}").apply { mkdirs() }
        val cached = if (book.format == BookFormat.IMAGE_FOLDER.name) {
            val children = remote.list(book.remotePath.ifBlank { book.location })
            val images = children.filter { !it.directory && FormatDetector.isImageName(it.name) }
            if (images.isEmpty()) throw UnsupportedBookException("远程文件夹里没有图片")
            images.forEach { child ->
                val fileName = child.name.substringAfterLast('/').ifBlank { "page" }
                remote.download(child.path, File(destDir, fileName), onProgress)
            }
            destDir.absolutePath
        } else {
            val ext = FormatDetector.extension(book.remotePath.ifBlank { book.title }).ifBlank { "bin" }
            val dest = File(destDir, safeName(book.title) + ".$ext")
            remote.download(book.remotePath.ifBlank { book.location }, dest, onProgress)
            dest.absolutePath
        }
        database.books().update(book.copy(cachedPath = cached, sizeBytes = File(cached).let { if (it.isFile) it.length() else book.sizeBytes }))
        mergeDuplicateProgress(book)
    }

    suspend fun deleteBook(bookId: Long) = withContext(Dispatchers.IO) {
        val book = database.books().get(bookId) ?: return@withContext
        deleteOwnedFiles(book)
        database.books().delete(bookId)
    }

    suspend fun deleteSource(sourceId: Long) = withContext(Dispatchers.IO) {
        database.sources().delete(sourceId)
    }

    suspend fun book(bookId: Long): BookEntity? = withContext(Dispatchers.IO) { database.books().get(bookId) }

    suspend fun progress(bookId: Long): ProgressEntity? = withContext(Dispatchers.IO) { database.progress().get(bookId) }

    suspend fun bookmarks(bookId: Long): List<BookmarkEntity> = withContext(Dispatchers.IO) {
        database.bookmarks().forBook(bookId)
    }

    suspend fun saveProgress(bookId: Long, locator: String, percent: Float) = withContext(Dispatchers.IO) {
        database.progress().upsert(
            ProgressEntity(
                bookId = bookId,
                locator = locator,
                percent = percent.coerceIn(0f, 1f),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun addBookmark(bookId: Long, locator: String, label: String) = withContext(Dispatchers.IO) {
        database.bookmarks().insert(
            BookmarkEntity(
                bookId = bookId,
                locator = locator,
                label = label,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteBookmark(id: Long) = withContext(Dispatchers.IO) { database.bookmarks().delete(id) }

    suspend fun openNovel(bookId: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): NovelContent =
        withContext(Dispatchers.IO) {
            val book = database.books().get(bookId) ?: throw UnsupportedBookException("找不到这本书")
            val file = ensureFile(book, onProgress)
            val format = runCatching { BookFormat.valueOf(book.format) }.getOrDefault(BookFormat.UNSUPPORTED)
            val content = when (format) {
                BookFormat.TXT -> {
                    val decoded = TextEncoding.decode(file.readBytes())
                    val chapters = TxtChapters.split(decoded.text).map { chapter ->
                        NovelChapter(chapter.title, decoded.text.substring(chapter.start, chapter.end).trim())
                    }
                    NovelContent(book.title, book.author, chapters)
                }
                BookFormat.EPUB -> EpubParser.parse(file)
                BookFormat.MOBI, BookFormat.AZW3 -> MobiParser.parse(file)
                else -> throw UnsupportedBookException("这个文件不能当小说打开")
            }
            database.books().update(
                book.copy(
                    title = content.title.ifBlank { book.title },
                    author = content.author.ifBlank { book.author },
                    lastOpenedAt = System.currentTimeMillis(),
                ),
            )
            content
        }

    suspend fun openPaged(bookId: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): PagedContent =
        withContext(Dispatchers.IO) {
            val book = database.books().get(bookId) ?: throw UnsupportedBookException("找不到这本书")
            val format = runCatching { BookFormat.valueOf(book.format) }.getOrDefault(BookFormat.UNSUPPORTED)
            val content = when (format) {
                BookFormat.PDF -> {
                    val file = ensureFile(book, onProgress)
                    PagedContent(book.title, format, emptyList(), file, BitmapIO.pdfPageCount(file))
                }
                BookFormat.CBZ, BookFormat.ZIP_IMAGES -> {
                    val file = ensureFile(book, onProgress)
                    val pages = extractZip(book.id, file).map { PageRef.FilePage(it.absolutePath) }
                    PagedContent(book.title, format, pages, null, 0)
                }
                BookFormat.CBR -> {
                    val file = ensureFile(book, onProgress)
                    val dir = File(context.filesDir, "cache-books/${book.id}/images")
                    val existing = sortedImages(dir)
                    val files = existing.ifEmpty { CbrExtractor.extractImages(file, dir) }
                    PagedContent(book.title, format, files.map { PageRef.FilePage(it.absolutePath) }, null, 0)
                }
                BookFormat.IMAGE_FOLDER -> {
                    val cached = sortedImages(File(book.cachedPath))
                    if (cached.isNotEmpty()) {
                        PagedContent(book.title, format, cached.map { PageRef.FilePage(it.absolutePath) }, null, 0)
                    } else if (book.location.startsWith("saf:")) {
                        val pages = listSafImages(book.location).map { PageRef.UriPage(it.toString()) }
                        if (pages.isEmpty()) throw UnsupportedBookException("文件夹里没有图片，或已失去访问权限")
                        PagedContent(book.title, format, pages, null, 0)
                    } else {
                        cacheBook(book.id, onProgress)
                        val refreshed = database.books().get(book.id) ?: book
                        val files = sortedImages(File(refreshed.cachedPath))
                        if (files.isEmpty()) throw UnsupportedBookException("未缓存，当前无法离线打开")
                        PagedContent(book.title, format, files.map { PageRef.FilePage(it.absolutePath) }, null, 0)
                    }
                }
                else -> throw UnsupportedBookException("这个文件不能当漫画或 PDF 打开")
            }
            database.books().update(book.copy(lastOpenedAt = System.currentTimeMillis()))
            content
        }

    private suspend fun importLocalFile(sourceId: Long, name: String, uri: Uri) {
        val dest = File(context.filesDir, "local/${UUID.randomUUID()}/${safeName(name)}")
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw UnsupportedBookException("无法读取文件")
        val header = dest.inputStream().use { input ->
            val buffer = ByteArray(128)
            val count = input.read(buffer)
            if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }
        val zipPaths = if (FormatDetector.isZip(header)) {
            ZipFile(dest).use { zip -> zip.entries().toList().map { it.name } }
        } else {
            null
        }
        val mimetype = if (FormatDetector.isZip(header)) ZipImages.readMimetype(dest) else null
        val detection = FormatDetector.detectFile(name, header, zipPaths, mimetype)
        if (detection.format == BookFormat.UNSUPPORTED || detection.format == BookFormat.IMAGE_FOLDER) {
            dest.delete()
            throw UnsupportedBookException(detection.error ?: "不支持的格式：$name")
        }
        var title = name.substringBeforeLast('.').ifBlank { name }
        var author = ""
        if (detection.format == BookFormat.EPUB) {
            runCatching { EpubParser.parse(dest) }.getOrNull()?.let {
                title = it.title.ifBlank { title }
                author = it.author
            }
        }
        database.books().insert(
            BookEntity(
                sourceId = sourceId,
                title = title,
                author = author,
                format = detection.format.name,
                kind = (detection.format.kind() ?: BookKind.NOVEL).name,
                location = dest.absolutePath,
                cachedPath = dest.absolutePath,
                addedAt = System.currentTimeMillis(),
                sizeBytes = dest.length(),
            ),
        )
    }

    private suspend fun importImageFolder(sourceId: Long, name: String, treeUri: Uri, folder: DocumentFile) {
        val documentId = try {
            DocumentsContract.getDocumentId(folder.uri)
        } catch (_: Exception) {
            DocumentsContract.getTreeDocumentId(treeUri)
        }
        database.books().insert(
            BookEntity(
                sourceId = sourceId,
                title = name,
                format = BookFormat.IMAGE_FOLDER.name,
                kind = BookKind.COMIC.name,
                location = "saf:$treeUri\n$documentId",
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun listSafImages(location: String): List<Uri> {
        val payload = location.removePrefix("saf:")
        val tree = payload.substringBefore('\n')
        val documentId = payload.substringAfter('\n')
        val treeUri = Uri.parse(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val images = ArrayList<Pair<String, Uri>>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                if (!FormatDetector.isImageName(name)) continue
                val id = cursor.getString(idIndex) ?: continue
                images += name to DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            }
        }
        return images.sortedWith(compareBy(com.numbear.manjuan.core.NaturalSort) { it.first }).map { it.second }
    }

    private suspend fun ensureFile(book: BookEntity, onProgress: (Long, Long) -> Unit): File {
        if (book.cachedPath.isNotBlank() && File(book.cachedPath).isFile) return File(book.cachedPath)
        if (book.location.startsWith("/") && File(book.location).isFile) return File(book.location)
        val source = database.sources().get(book.sourceId)
        if (source?.type == WEBDAV) {
            try {
                cacheBook(book.id, onProgress)
            } catch (error: UnsupportedBookException) {
                throw UnsupportedBookException("未缓存，当前无法离线打开。${error.message}")
            }
            val updated = database.books().get(book.id)
            val path = updated?.cachedPath.orEmpty()
            if (path.isNotBlank() && File(path).isFile) return File(path)
        }
        throw UnsupportedBookException("未缓存，当前无法离线打开")
    }

    private fun extractZip(bookId: Long, zipFile: File): List<File> {
        val dir = File(context.filesDir, "cache-books/$bookId/images")
        val existing = sortedImages(dir)
        if (existing.isNotEmpty()) return existing
        dir.mkdirs()
        val names = ZipImages.list(zipFile)
        if (names.isEmpty()) throw UnsupportedBookException("压缩包里没有可显示的图片")
        ZipFile(zipFile, Charsets.UTF_8).use { zip ->
            names.forEach { name ->
                val entry = zip.getEntry(name) ?: return@forEach
                val out = File(dir, name.substringAfterLast('/').substringAfterLast('\\'))
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        return sortedImages(dir).ifEmpty { throw UnsupportedBookException("压缩包里没有可显示的图片") }
    }

    private suspend fun mergeDuplicateProgress(book: BookEntity) {
        if (book.remotePath.isBlank()) return
        val rows = database.books().listRemote(book.sourceId, book.remotePath)
        val primary = database.progress().get(book.id)?.toReading()
        rows.filter { it.id != book.id }.forEach { other ->
            val merged = ProgressMerge.merge(primary, database.progress().get(other.id)?.toReading())
            if (merged != null) {
                database.progress().upsert(
                    ProgressEntity(book.id, merged.locator, merged.percent, merged.updatedAt),
                )
            }
            deleteOwnedFiles(other)
            database.books().delete(other.id)
        }
    }

    private fun deleteOwnedFiles(book: BookEntity) {
        val root = context.filesDir.absolutePath
        listOf(book.cachedPath, book.location).forEach { path ->
            if (path.startsWith(root)) File(path).let { file ->
                if (file.isFile) file.delete()
                file.parentFile?.takeIf { it.absolutePath.startsWith(root) && it.name.length > 8 }?.deleteRecursively()
            }
        }
        File(context.filesDir, "cache-books/${book.id}").deleteRecursively()
        File(context.filesDir, "local").walkTopDown().maxDepth(2).forEach { dir ->
            if (dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete()
        }
    }

    private suspend fun ensureLocalSource(): Long {
        database.sources().firstOfType(LOCAL)?.let { return it.id }
        return database.sources().insert(
            SourceEntity(type = LOCAL, displayName = "本地", createdAt = System.currentTimeMillis()),
        )
    }

    private suspend fun requireSource(id: Long): SourceEntity =
        database.sources().get(id) ?: throw UnsupportedBookException("找不到 WebDAV 账号")

    private fun client(source: SourceEntity): OkHttpWebDavClient =
        client(source.baseUrl, source.username, cipher.decrypt(source.passwordCipher), source.rootPath)

    private fun client(url: String, username: String, password: String, root: String): OkHttpWebDavClient {
        val base = OkHttpWebDavClient.normalizeBase(url)
        val extra = root.trim().trim('/')
        val joined = if (extra.isEmpty()) base else OkHttpWebDavClient.normalizeBase(base + extra + "/")
        return OkHttpWebDavClient(joined, username, password)
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "未命名"

    private fun sortedImages(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty().filter { FormatDetector.isImageName(it.name) }
            .sortedWith(compareBy(com.numbear.manjuan.core.NaturalSort) { it.name })
    }

    private fun safeName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80).ifBlank { "book" }

    private fun ProgressEntity.toReading() = ReadingProgress(locator, percent, updatedAt)

    companion object {
        const val LOCAL = "LOCAL"
        const val WEBDAV = "WEBDAV"
    }
}
