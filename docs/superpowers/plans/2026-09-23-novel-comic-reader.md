# 漫卷 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a native Kotlin/Compose Android reader (漫卷, `com.numbear.manjuan`) that opens local TXT/EPUB/comics/PDF and WebDAV books with whole-book offline cache.

**Architecture:** `:core` holds pure parsing and WebDAV. `:data` holds Room, Keystore crypto, SAF import, and cache files. `:app` holds Compose screens in feature packages listed in the spec.

**Tech Stack:** Kotlin 2.2.21, AGP 8.13.2, Compose BOM 2026.06.01, Room 2.8.4, OkHttp 4.12.0, minSdk 26, compileSdk/targetSdk 36.

## Global Constraints

- Kotlin + Jetpack Compose only (not Flutter, not a WebView shell).
- Min SDK 26, compileSdk 36, targetSdk 36.
- Package `com.numbear.manjuan`. App name 漫卷.
- Day-one sources: SAF local files/folders and WebDAV.
- Formats: TXT, EPUB, MOBI/AZW3 best-effort, PDF, CBZ, CBR, ZIP images, image folders.
- Detect by extension + magic bytes; Chinese errors for unsupported files.
- Room for books, sources, progress, bookmarks, WebDAV configs.
- Encrypt WebDAV passwords with Android Keystore.
- Merge progress when a WebDAV book is cached onto an existing row.
- Out of scope: iOS, cloud sync, TTS, bookstore scraping, OPDS.
- `./gradlew :app:assembleDebug` must succeed.

---

### Task 1: Gradle shell, Room, design, README

**Files:**
- Create: Gradle build, `:app` Activity + empty bookshelf, `:data` Room entities
- Create: `docs/superpowers/specs/2026-09-23-novel-comic-reader-design.md`
- Modify: `README.md`

**Interfaces:**
- Produces: `ManjuanDatabase`, `BookEntity`, `SourceEntity`, `ProgressEntity`, `BookmarkEntity`

- [ ] Add version catalog, wrapper, and modules `:app`, `:core`, `:data`
- [ ] Add Room schema version 1
- [ ] Add Compose bookshelf placeholder that navigates to Add and Settings
- [ ] Replace README with Chinese build, local, and WebDAV instructions
- [ ] `./gradlew :app:assembleDebug`

### Task 2: Local TXT/EPUB novel reader

**Files:**
- Create: `:core` `FormatDetector`, `TextEncoding`, `TxtChapters`, `EpubParser`, `TextPaginator`
- Test: `:core` unit tests for those types
- Create: `com.numbear.manjuan.reader.text.NovelReaderScreen`

**Interfaces:**
- Produces: `FormatDetector.detectFile`, `EpubParser.parse`, `TxtChapters.split`, `NovelContent`

- [ ] Failing tests for detection, encoding, chapters, pagination, synthetic EPUB
- [ ] Implement parsers until `:core:test` passes
- [ ] SAF multi-file import copies into app storage and inserts a book
- [ ] Novel reader restores locator `c={index};o={offset}`

### Task 3: CBZ and image folders

**Files:**
- Create: `ZipImages`, `com.numbear.manjuan.reader.comic.ComicReaderScreen`

**Interfaces:**
- Produces: `ZipImages.list`, comic locator `p={index}`

- [ ] Natural-sort test for image names
- [ ] Import CBZ and SAF image directories
- [ ] Vertical comic reader with fit-width

### Task 4: PDF

**Files:**
- Create: `com.numbear.manjuan.reader.pdf.PdfReaderScreen`
- Create: `PdfBitmaps` in `:data`

**Interfaces:**
- Consumes: `PdfRenderer`
- Produces: page count and a bitmap for a page index

- [ ] Route `BookFormat.PDF` to the PDF reader
- [ ] Render pages on a background dispatcher

### Task 5: WebDAV browse and add

**Files:**
- Create: `WebDavXml`, `OkHttpWebDavClient`
- Test: PROPFIND fixture and MockWebServer

**Interfaces:**
- Produces: `OkHttpWebDavClient.test`, `list`, `download`

- [ ] Account form with test connection
- [ ] Browse and add a remote file without downloading the body yet

### Task 6: Whole-book cache

**Files:**
- Create: cache writer under `filesDir/cache-books/{bookId}/`
- Test: `ProgressMerge.merge`

**Interfaces:**
- Produces: `LibraryRepository.cacheBook`
- Consumes: `ProgressMerge`

- [ ] Cache button downloads the remote file or image directory
- [ ] Re-adding the same remote path reuses the row and keeps progress
- [ ] Open uses `cachedPath` offline; otherwise shows 未缓存

### Task 7: MOBI, CBR, UX defaults

**Files:**
- Create: `MobiParser`, `CbrExtractor`, settings DataStore, reader chrome

**Interfaces:**
- Produces: `MobiParser.parse`, `ReaderSettings`

- [ ] Synthetic MOBI and PalmDOC LZ77 tests
- [ ] CBR extract; unsupported RAR shows a Chinese error
- [ ] Themes, tap zones, bookmarks, volume keys, orientation, keep screen on

### Task 8: Verify

- [ ] `./gradlew :core:test :app:assembleDebug`
- [ ] README matches the APK path and the local / WebDAV steps
