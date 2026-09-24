package com.numbear.manjuan.data.repo

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.numbear.manjuan.core.BookCache
import com.numbear.manjuan.core.BookFormat
import com.numbear.manjuan.core.BookKind
import com.numbear.manjuan.core.CbrExtractor
import com.numbear.manjuan.core.EpubParser
import com.numbear.manjuan.core.FolderImport
import com.numbear.manjuan.core.FormatDetector
import com.numbear.manjuan.core.ImageSniff
import com.numbear.manjuan.core.LibraryNames
import com.numbear.manjuan.core.MobiParser
import com.numbear.manjuan.core.LocatorCodec
import com.numbear.manjuan.core.NovelChapter
import com.numbear.manjuan.core.NovelContent
import com.numbear.manjuan.core.OkHttpWebDavClient
import com.numbear.manjuan.core.RemoteText
import com.numbear.manjuan.core.ProgressMerge
import com.numbear.manjuan.core.ReadingProgress
import com.numbear.manjuan.core.TextEncoding
import com.numbear.manjuan.core.TxtChapters
import com.numbear.manjuan.core.UnsupportedBookException
import com.numbear.manjuan.core.WebDavBooks
import com.numbear.manjuan.core.WebDavEntry
import com.numbear.manjuan.core.WebDavPaths
import com.numbear.manjuan.core.WebDavScan
import com.numbear.manjuan.core.WebDavStatus
import com.numbear.manjuan.core.ZipImages
import com.numbear.manjuan.core.kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import com.numbear.manjuan.data.crypto.WebDavCipher
import com.numbear.manjuan.data.db.BookEntity
import com.numbear.manjuan.data.db.BookmarkEntity
import com.numbear.manjuan.data.db.ManjuanDatabase
import com.numbear.manjuan.data.db.ProgressEntity
import com.numbear.manjuan.data.db.SourceEntity
import com.numbear.manjuan.data.open.BitmapIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InterruptedIOException
import java.util.UUID
import java.util.zip.ZipFile

class LibraryRepository(
    private val context: Context,
    private val database: ManjuanDatabase,
    private val cipher: WebDavCipher,
) {
    fun observeBooks(): Flow<List<BookEntity>> = database.books().observeAll()

    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) {
        BookCache.size(File(context.filesDir, BookCache.DIR))
    }

    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, BookCache.DIR)
        val freed = BookCache.size(root)
        val cachedIds = database.books().all()
            .filter { BookCache.storedInside(root.absolutePath, it.cachedPath) }
            .map { it.id }
        root.deleteRecursively()
        cachedIds.forEach { database.books().clearCachedPath(it) }
        freed
    }

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
        val rootId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return@withContext ImportReport(0, listOf("无法打开所选文件夹"))
        }
        val sourceId = ensureLocalSource()
        val errors = ArrayList<String>()
        var added = 0
        val visited = HashSet<String>()
        fun note(message: String) = errors.noteCapped(message)
        // DocumentFile.listFiles() stays shallow or empty on some SAF providers.
        // DocumentsContract child queries follow the granted tree, including nested folders.
        suspend fun walk(documentId: String, displayName: String, depth: Int) {
            if (!visited.add(documentId)) return
            val children = listSafChildren(uri, documentId)
            if (children == null) {
                note("$displayName：无法读取文件夹")
                return
            }
            val plan = FolderImport.plan(
                children = children,
                depth = depth,
                nameOf = { it.name },
                isDirectory = { it.directory },
            )
            plan.books.forEach { file ->
                val name = file.name.ifBlank { "未命名" }
                try {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, file.documentId)
                    importLocalFile(sourceId, name, docUri)
                    added++
                } catch (error: UnsupportedBookException) {
                    note("$name：${error.message}")
                } catch (_: Exception) {
                    note("$name：无法读取文件")
                }
            }
            if (plan.imageFolder) {
                val title = displayName.ifBlank { "图片文件夹" }
                try {
                    importImageFolder(sourceId, title, uri, documentId)
                    added++
                } catch (error: UnsupportedBookException) {
                    note("$title：${error.message}")
                } catch (_: Exception) {
                    note("$title：无法加入")
                }
            }
            plan.directories.forEach { dir ->
                walk(dir.documentId, dir.name.ifBlank { "文件夹" }, depth + 1)
            }
        }
        walk(rootId, queryDisplayName(uri, rootId) ?: "所选文件夹", 0)
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

    suspend fun updateWebDav(
        id: Long,
        name: String,
        url: String,
        username: String,
        password: String,
        root: String,
    ) = withContext(Dispatchers.IO) {
        val existing = database.sources().get(id) ?: throw UnsupportedBookException("找不到 WebDAV 账号")
        val secret = if (password.isNotEmpty()) password else cipher.decrypt(existing.passwordCipher)
        val status = testWebDav(url, username, secret, root)
        if (status is WebDavStatus.Failed) throw UnsupportedBookException(status.message)
        database.sources().update(
            existing.copy(
                displayName = name.ifBlank { url.trim() },
                baseUrl = url.trim(),
                username = username,
                passwordCipher = if (password.isEmpty()) existing.passwordCipher else cipher.encrypt(password),
                rootPath = root.trim(),
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

    suspend fun testWebDavAccount(
        existingId: Long?,
        url: String,
        username: String,
        password: String,
        root: String,
    ): WebDavStatus = withContext(Dispatchers.IO) {
        val secret = if (existingId == null || password.isNotEmpty()) {
            password
        } else {
            val existing = database.sources().get(existingId)
                ?: return@withContext WebDavStatus.Failed("找不到 WebDAV 账号")
            cipher.decrypt(existing.passwordCipher)
        }
        testWebDav(url, username, secret, root)
    }

    suspend fun listWebDav(sourceId: Long, path: String): List<WebDavEntry> = withContext(Dispatchers.IO) {
        client(requireSource(sourceId)).list(path)
    }

    suspend fun scanWebDav(
        sourceId: Long,
        path: String,
        onProgress: (RemoteScanProgress) -> Unit = {},
    ): RemoteScanReport = withContext(Dispatchers.IO) {
        val remote = client(requireSource(sourceId))
        val errors = ArrayList<String>()
        val books = ArrayList<WebDavEntry>()
        var errorCount = 0
        val job = coroutineContext[Job]
        fun note(message: String) {
            errorCount++
            errors.noteCapped(message)
        }
        fun publish(imported: Int, skipped: Int, current: String) {
            publishScan(
                onProgress,
                RemoteScanProgress(books.size, imported, skipped, errorCount, current),
            )
        }
        fun checkActive() {
            if (job?.isActive == false) throw CancellationException()
        }
        WebDavScan.forEachBook(
            startPath = path,
            isActive = { job?.isActive != false },
            list = { folder ->
                checkActive()
                try {
                    remote.list(folder)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val label = folder.trim().trimEnd('/').substringAfterLast('/').ifBlank { "当前目录" }
                    note("$label：${error.message ?: "无法列出目录"}")
                    publish(0, 0, label)
                    emptyList()
                }
            },
            onDirectory = { folder ->
                val label = folder.trim().trimEnd('/').substringAfterLast('/').ifBlank { "当前目录" }
                publish(0, 0, label)
            },
            onBook = { entry ->
                checkActive()
                books += entry
                publish(0, 0, entry.name)
            },
        )
        var imported = 0
        var skipped = 0
        for (entry in books) {
            coroutineContext.ensureActive()
            try {
                when (insertRemote(sourceId, entry)) {
                    is RemoteInsert.Created -> imported++
                    is RemoteInsert.Existing -> skipped++
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: UnsupportedBookException) {
                note("${entry.name}：${error.message}")
            } catch (_: Exception) {
                note("${entry.name}：无法导入")
            }
            publish(imported, skipped, entry.name)
        }
        RemoteScanReport(books.size, imported, skipped, errors.toList())
    }

    suspend fun addRemote(sourceId: Long, entry: WebDavEntry): Long = withContext(Dispatchers.IO) {
        when (val result = insertRemote(sourceId, entry)) {
            is RemoteInsert.Created -> result.id
            is RemoteInsert.Existing -> result.id
        }
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
        val destDir = File(context.filesDir, "${BookCache.DIR}/${book.id}").apply { mkdirs() }
        val remoteName = WebDavBooks.displayFileName(book.title, book.remotePath.ifBlank { book.location })
        val cached = if (book.format == BookFormat.IMAGE_FOLDER.name) {
            val children = blockingWebDav { remote.list(book.remotePath.ifBlank { book.location }) }
            val images = children.filter { !it.directory && FormatDetector.isImageName(it.name.ifBlank { it.path }) }
            if (images.isEmpty()) throw UnsupportedBookException("远程文件夹里没有图片")
            val imageDir = File(destDir, "images").apply { mkdirs() }
            images.forEachIndexed { index, child ->
                coroutineContext.ensureActive()
                blockingWebDav { remote.download(child.path, File(imageDir, remoteImageName(index, child)), onProgress) }
            }
            File(imageDir, PARTIAL_MARK).delete()
            imageDir.absolutePath
        } else {
            val ext = FormatDetector.extension(remoteName).ifBlank { "bin" }
            val dest = File(destDir, safeName(book.title) + ".$ext")
            blockingWebDav { remote.download(book.remotePath.ifBlank { book.location }, dest, onProgress) }
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

    suspend fun prepareReader(bookId: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): String =
        withContext(Dispatchers.IO) {
            val book = database.books().get(bookId) ?: throw UnsupportedBookException("找不到这本书")
            val source = database.sources().get(book.sourceId)
            if (source?.type == WEBDAV && !cacheReady(book)) {
                val streamed = book.format == BookFormat.TXT.name || book.format == BookFormat.IMAGE_FOLDER.name
                if (!streamed) cacheBook(book.id, onProgress)
            }
            val fresh = database.books().get(bookId) ?: book
            val resolved = WebDavBooks.resolve(
                formatName = fresh.format,
                kindName = fresh.kind,
                location = fresh.location,
                remotePath = fresh.remotePath,
                title = fresh.title,
                header = sniffCached(fresh),
                cachedImageCount = cachedImageCount(fresh),
            )
            var kind = resolved.format.kind() ?: throw UnsupportedBookException("无法打开这种书")
            if (resolved.format == BookFormat.MOBI || resolved.format == BookFormat.AZW3) {
                val file = ensureFile(fresh, onProgress)
                val opening = MobiParser.opening(file)
                if (opening.pictureBook) {
                    if (opening.images.isEmpty()) {
                        throw UnsupportedBookException("这本 MOBI 是图片书，但没有可显示的图片")
                    }
                    kind = BookKind.COMIC
                }
            }
            if (fresh.format != resolved.format.name || fresh.kind != kind.name) {
                database.books().update(fresh.copy(format = resolved.format.name, kind = kind.name))
            }
            kind.name
        }

    suspend fun openNovel(bookId: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): NovelContent =
        withContext(Dispatchers.IO) {
            val book = database.books().get(bookId) ?: throw UnsupportedBookException("找不到这本书")
            val source = database.sources().get(book.sourceId)
            if (source?.type == WEBDAV && book.format == BookFormat.TXT.name && !cacheReady(book)) {
                val saved = database.progress().get(book.id)
                val content = remoteText(
                    book,
                    LocatorCodec.chapter(saved?.locator.orEmpty()),
                    LocatorCodec.offset(saved?.locator.orEmpty()),
                    onProgress,
                )
                rememberOpened(book, content.title, content.author, BookFormat.TXT.name, BookKind.NOVEL.name)
                return@withContext content
            }
            val file = ensureFile(book, onProgress)
            val resolved = WebDavBooks.resolve(
                formatName = book.format,
                kindName = book.kind,
                location = book.location,
                remotePath = book.remotePath,
                title = book.title,
                header = fileHeader(file),
                cachedImageCount = cachedImageCount(database.books().get(book.id) ?: book),
            )
            if (resolved.format.kind() != com.numbear.manjuan.core.BookKind.NOVEL) {
                val kind = resolved.format.kind()
                if (kind != null) {
                    val current = database.books().get(book.id) ?: book
                    database.books().update(current.copy(format = resolved.format.name, kind = kind.name))
                }
                throw UnsupportedBookException("这是漫画或 PDF，不能当小说打开")
            }
            val format = resolved.format
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
            val stored = database.books().get(book.id) ?: book
            val kind = resolved.format.kind()?.name ?: stored.kind
            database.books().update(
                stored.copy(
                    title = content.title.ifBlank { stored.title },
                    author = content.author.ifBlank { stored.author },
                    format = resolved.format.name,
                    kind = kind,
                    lastOpenedAt = System.currentTimeMillis(),
                ),
            )
            content
        }

    suspend fun extendNovel(bookId: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): NovelContent =
        withContext(Dispatchers.IO) {
            val book = database.books().get(bookId) ?: throw UnsupportedBookException("找不到这本书")
            if (book.format != BookFormat.TXT.name || cacheReady(book)) return@withContext openNovel(bookId, onProgress)
            val content = remoteGate(book.id).withLock { appendText(book, onProgress) }
            rememberOpened(book, content.title, content.author, BookFormat.TXT.name, BookKind.NOVEL.name)
            content
        }

    suspend fun extendPaged(bookId: Long, page: Int, onProgress: (Long, Long) -> Unit = { _, _ -> }): PagedContent =
        withContext(Dispatchers.IO) {
            val book = database.books().get(bookId) ?: throw UnsupportedBookException("找不到这本书")
            val source = database.sources().get(book.sourceId)
            if (source?.type != WEBDAV || cacheReady(book)) return@withContext openPaged(bookId, onProgress)
            openRemoteImages(book, page, onProgress)
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
                    val dir = File(context.filesDir, "${BookCache.DIR}/${book.id}/images")
                    val existing = sortedImages(dir)
                    val files = existing.ifEmpty { CbrExtractor.extractImages(file, dir) }
                    PagedContent(book.title, format, files.map { PageRef.FilePage(it.absolutePath) }, null, 0)
                }
                BookFormat.MOBI, BookFormat.AZW3 -> {
                    val file = ensureFile(book, onProgress)
                    val images = MobiParser.imagePages(file)
                    val dir = File(context.filesDir, "${BookCache.DIR}/${book.id}/mobi-pages")
                    dir.mkdirs()
                    dir.listFiles()?.forEach { it.delete() }
                    val pages = images.mapIndexed { index, bytes ->
                        val out = File(dir, "page-%04d.${ImageSniff.extension(bytes)}".format(index + 1))
                        out.writeBytes(bytes)
                        PageRef.FilePage(out.absolutePath)
                    }
                    PagedContent(book.title, format, pages, null, 0)
                }
                BookFormat.IMAGE_FOLDER -> {
                    val source = database.sources().get(book.sourceId)
                    if (source?.type == WEBDAV && !cacheReady(book)) {
                        val saved = LocatorCodec.pageIndex(database.progress().get(book.id)?.locator.orEmpty())
                        return@withContext openRemoteImages(book, saved, onProgress).also {
                            database.books().update((database.books().get(book.id) ?: book).copy(lastOpenedAt = System.currentTimeMillis()))
                        }
                    }
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
            val stored = database.books().get(book.id) ?: book
            database.books().update(stored.copy(lastOpenedAt = System.currentTimeMillis()))
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

    private suspend fun importImageFolder(sourceId: Long, name: String, treeUri: Uri, documentId: String) {
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

    private suspend fun insertRemote(sourceId: Long, entry: WebDavEntry): RemoteInsert {
        val existing = database.books().findRemote(sourceId, entry.path)
        if (existing != null) return RemoteInsert.Existing(existing.id)
        val header = if (entry.directory) {
            ByteArray(0)
        } else {
            val remote = client(requireSource(sourceId))
            blockingWebDav { remote.peek(entry.path) }
        }
        val detected = WebDavBooks.classify(entry.name, entry.path, entry.directory, header)
        if (detected.format == BookFormat.UNSUPPORTED) {
            throw UnsupportedBookException(detected.error ?: "不支持的格式：${entry.name}")
        }
        val kind = detected.format.kind() ?: throw UnsupportedBookException("不支持的格式：${entry.name}")
        val titleSource = entry.name.ifBlank { WebDavBooks.displayFileName(entry.name, entry.path) }
        val id = database.books().insert(
            BookEntity(
                sourceId = sourceId,
                title = titleSource.substringBeforeLast('.').ifBlank { titleSource },
                format = detected.format.name,
                kind = kind.name,
                location = entry.path,
                remotePath = entry.path,
                addedAt = System.currentTimeMillis(),
                sizeBytes = entry.size,
            ),
        )
        return RemoteInsert.Created(id)
    }

    private data class SafNode(
        val documentId: String,
        val name: String,
        val directory: Boolean,
        val mime: String,
    )

    private fun listSafChildren(treeUri: Uri, documentId: String): List<SafNode>? {
        val raw = querySafChildren(treeUri, documentId) ?: return null
        return raw.map { node ->
            if (node.directory || LibraryNames.isJunk(node.name)) return@map node
            val knownFile = LibraryNames.isBookFile(node.name) || FormatDetector.isImageName(node.name)
            val ambiguous = node.mime.isBlank() || node.mime.equals("application/octet-stream", ignoreCase = true)
            if (!ambiguous || knownFile) return@map node
            val nested = querySafChildren(treeUri, node.documentId)
            if (!nested.isNullOrEmpty()) node.copy(directory = true) else node
        }
    }

    private fun querySafChildren(treeUri: Uri, documentId: String): List<SafNode>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS,
                ),
                null,
                null,
                null,
            )
        } catch (_: Exception) {
            return null
        } ?: return null
        return cursor.use {
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            if (idIndex < 0) return emptyList()
            val nodes = ArrayList<SafNode>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex) ?: continue
                val rawName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                val name = rawName.ifBlank { id.substringAfterLast(':').substringAfterLast('/') }
                val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
                val flags = if (flagsIndex >= 0 && !cursor.isNull(flagsIndex)) cursor.getInt(flagsIndex) else 0
                nodes += SafNode(id, name, looksLikeDirectory(mime, flags), mime)
            }
            nodes
        }
    }

    private fun queryDisplayName(treeUri: Uri, documentId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikeDirectory(mime: String, flags: Int): Boolean {
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) return true
        if (mime.equals("application/vnd.google-apps.folder", ignoreCase = true)) return true
        if (mime.endsWith("/directory", ignoreCase = true) || mime.endsWith("/folder", ignoreCase = true)) return true
        val dirFlags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
            DocumentsContract.Document.FLAG_DIR_PREFERS_GRID
        return flags and dirFlags != 0
    }

    private fun publishScan(onProgress: (RemoteScanProgress) -> Unit, progress: RemoteScanProgress) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onProgress(progress)
        } else {
            Handler(Looper.getMainLooper()).post { onProgress(progress) }
        }
    }

    private sealed class RemoteInsert {
        data class Created(val id: Long) : RemoteInsert()
        data class Existing(val id: Long) : RemoteInsert()
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
        val source = database.sources().get(book.sourceId)
        val local = WebDavPaths.localReadablePath(
            sourceType = source?.type.orEmpty(),
            location = book.location,
            cachedPath = book.cachedPath,
            remotePath = book.remotePath,
        ) { path -> File(path).isFile }
        if (local != null) return File(local)
        if (source?.type == WEBDAV) {
            try {
                cacheBook(book.id, onProgress)
            } catch (error: CancellationException) {
                throw error
            } catch (error: UnsupportedBookException) {
                val detail = error.message.orEmpty()
                if (detail.contains("网络超时") || detail.contains("无法下载")) throw error
                throw UnsupportedBookException(detail.ifBlank { "未缓存，当前无法离线打开" })
            }
            val updated = database.books().get(book.id)
            val path = updated?.cachedPath.orEmpty()
            if (path.isNotBlank() && File(path).isFile) return File(path)
            throw UnsupportedBookException("未缓存，当前无法离线打开")
        }
        throw UnsupportedBookException("未缓存，当前无法离线打开")
    }

    private fun extractZip(bookId: Long, zipFile: File): List<File> {
        val dir = File(context.filesDir, "${BookCache.DIR}/$bookId/images")
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
        File(context.filesDir, "${BookCache.DIR}/${book.id}").deleteRecursively()
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

    private fun client(url: String, username: String, password: String, root: String): OkHttpWebDavClient =
        OkHttpWebDavClient(WebDavPaths.joinBase(url, root), username, password)

    private suspend fun <T> blockingWebDav(block: () -> T): T {
        try {
            return runInterruptible { block() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedIOException) {
            if (currentCoroutineContext().isActive) throw UnsupportedBookException("网络超时")
            throw CancellationException("下载已取消", error)
        } catch (error: InterruptedException) {
            throw CancellationException("下载已取消", error)
        }
    }

    private fun cacheReady(book: BookEntity): Boolean {
        if (book.cachedPath.isBlank()) return false
        val file = File(book.cachedPath)
        if (file.isFile) return true
        return file.isDirectory && cachedImageCount(book) > 0 && !File(file, PARTIAL_MARK).exists()
    }

    private val remoteGates = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    private fun remoteGate(bookId: Long): Mutex = remoteGates.getOrPut(bookId) { Mutex() }

    private fun streamFile(bookId: Long) = File(context.filesDir, "cache-books/$bookId/stream.bin")

    private suspend fun rememberOpened(book: BookEntity, title: String, author: String, format: String, kind: String) {
        val stored = database.books().get(book.id) ?: book
        database.books().update(
            stored.copy(
                title = title.ifBlank { stored.title },
                author = author.ifBlank { stored.author },
                format = format,
                kind = kind,
                lastOpenedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun remoteText(book: BookEntity, chapter: Int, offset: Int, onProgress: (Long, Long) -> Unit): NovelContent {
        return remoteGate(book.id).withLock {
            var content = appendText(book, onProgress)
            var guard = 0
            while (content.more && !RemoteText.covered(content.chapters, chapter, offset, complete = false) && guard < 4_000) {
                guard++
                val fresh = database.books().get(book.id) ?: book
                content = appendText(fresh, onProgress)
            }
            content
        }
    }

    private suspend fun appendText(book: BookEntity, onProgress: (Long, Long) -> Unit): NovelContent {
        val file = streamFile(book.id)
        file.parentFile?.mkdirs()
        val loaded = if (file.isFile) file.length() else 0L
        val known = book.sizeBytes
        if (known > 0 && loaded >= known) {
            finalizeText(book, file)
            return RemoteText.novel(book.title, book.author, file.readBytes(), known)
        }
        currentCoroutineContext().ensureActive()
        val remote = client(requireSource(book.sourceId))
        val part = blockingWebDav {
            remote.readRange(book.remotePath.ifBlank { book.location }, loaded, RemoteText.CHUNK_BYTES)
        }
        onProgress(loaded, if (part.total > 0) part.total else known)
        if (part.bytes.isEmpty()) {
            val total = if (part.total >= 0) part.total else loaded
            val current = database.books().get(book.id) ?: book
            database.books().update(current.copy(sizeBytes = total))
            if (file.isFile) finalizeText(current, file)
            val bytes = if (file.isFile) file.readBytes() else ByteArray(0)
            return RemoteText.novel(book.title, book.author, bytes, total)
        }
        file.appendBytes(part.bytes)
        val combinedSize = file.length()
        val total = when {
            part.total >= 0 -> part.total
            part.bytes.size < RemoteText.CHUNK_BYTES -> combinedSize
            else -> -1L
        }
        val current = database.books().get(book.id) ?: book
        database.books().update(current.copy(sizeBytes = if (total > 0) total else current.sizeBytes))
        onProgress(combinedSize, if (total > 0) total else -1L)
        val done = total >= 0 && combinedSize >= total
        if (done) finalizeText(database.books().get(book.id) ?: current, file)
        return RemoteText.novel(book.title, book.author, file.readBytes(), if (done) combinedSize else total)
    }

    private suspend fun finalizeText(book: BookEntity, file: File) {
        if (!file.isFile) return
        val current = database.books().get(book.id) ?: book
        database.books().update(current.copy(cachedPath = file.absolutePath, sizeBytes = file.length()))
    }

    private suspend fun openRemoteImages(book: BookEntity, page: Int, onProgress: (Long, Long) -> Unit): PagedContent {
        return remoteGate(book.id).withLock {
            val remote = client(requireSource(book.sourceId))
            val images = blockingWebDav { remote.list(book.remotePath.ifBlank { book.location }) }
                .filter { !it.directory && FormatDetector.isImageName(it.name.ifBlank { it.path }) }
            if (images.isEmpty()) throw UnsupportedBookException("远程文件夹里没有图片")
            val dir = File(context.filesDir, "cache-books/${book.id}/images").apply { mkdirs() }
            val target = RemoteText.imageTarget(page, images.size)
            var have = contiguousImages(dir, images.size)
            while (have < target) {
                currentCoroutineContext().ensureActive()
                val child = images[have]
                val dest = File(dir, remoteImageName(have, child))
                if (!dest.isFile || dest.length() == 0L) {
                    blockingWebDav { remote.download(child.path, dest, onProgress) }
                }
                have++
                onProgress(have.toLong(), images.size.toLong())
            }
            val complete = have >= images.size
            val marker = File(dir, PARTIAL_MARK)
            if (complete) marker.delete() else marker.writeText("partial")
            val current = database.books().get(book.id) ?: book
            database.books().update(current.copy(cachedPath = dir.absolutePath))
            val pages = (0 until have).map { index ->
                PageRef.FilePage(File(dir, remoteImageName(index, images[index])).absolutePath)
            }
            PagedContent(
                book.title,
                BookFormat.IMAGE_FOLDER,
                pages,
                null,
                0,
                remotePageCount = if (complete) 0 else images.size,
            )
        }
    }

    private fun contiguousImages(dir: File, total: Int): Int {
        var count = 0
        while (count < total) {
            val prefix = "%05d-".format(count + 1)
            val found = dir.listFiles().orEmpty().any { it.isFile && it.name.startsWith(prefix) && it.length() > 0L }
            if (!found) break
            count++
        }
        return count
    }

    private fun remoteImageName(index: Int, entry: WebDavEntry): String {
        val raw = WebDavBooks.displayFileName(entry.name, entry.path).ifBlank { "page" }
        return "%05d-%s".format(index + 1, safeName(raw))
    }

    private fun sniffCached(book: BookEntity): ByteArray {
        val file = File(book.cachedPath)
        if (!file.isFile) return ByteArray(0)
        return fileHeader(file)
    }

    private fun fileHeader(file: File): ByteArray = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(128)
            val count = input.read(buffer)
            if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun cachedImageCount(book: BookEntity): Int {
        val file = File(book.cachedPath)
        if (!file.isDirectory) return 0
        return file.listFiles().orEmpty().count { FormatDetector.isImageName(it.name) }
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
        const val PARTIAL_MARK = ".partial"
    }
}

private fun MutableList<String>.noteCapped(message: String) {
    if (size < 40) {
        add(message)
    } else if (lastOrNull() != "其余错误已省略") {
        add("其余错误已省略")
    }
}
