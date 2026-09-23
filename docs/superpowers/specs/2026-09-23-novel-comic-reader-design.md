# 漫卷 Novel / Comic Reader — Design

Date: 2026-09-23  
Package: `com.numbear.manjuan`  
App name: 漫卷

## Goal

A native Android reader for local novels and comics, plus WebDAV libraries with whole-book offline cache. v1 is an on-device app. It does not sync accounts, speak text, scrape stores, or speak OPDS.

## Stack

- Kotlin, Jetpack Compose, Material 3. Not Flutter. Not a WebView shell.
- Min SDK 26. Compile SDK and target SDK 36 (Android 16), the current stable platform. Android 17 is still a beta, so it is not the compile/target.
- Android Gradle Plugin 8.13.2, Kotlin 2.2.21, JDK 17 bytecode.
- Room for books, sources, progress, bookmarks, and WebDAV accounts.
- DataStore for reader preferences.
- OkHttp for WebDAV. Junrar for CBR (best-effort, RAR4).
- Android `PdfRenderer` for PDF pages. No embedded browser for EPUB; EPUB chapters are converted to plain text and drawn with Compose.

## Module boundaries

Gradle modules:

- `:core` — JVM library. Format detection, text encoding, TXT chapters, pagination, HTML-to-text, EPUB, MOBI/AZW3 best-effort, ZIP image listing, natural sort, WebDAV XML and client, progress merge. Unit-tested without an emulator.
- `:data` — Android library. Room, Android Keystore password cipher, SAF import, cache files, openers that turn a book row into chapters or page files.
- `:app` — Single Activity, Compose navigation, and feature packages.

Feature packages inside `:app` (the requested module list, as packages so navigation and theme stay in one composition graph and Room stays in `:data`):

| Package | Responsibility |
| --- | --- |
| `com.numbear.manjuan.bookshelf` | Recent / all / by-source shelf and search |
| `com.numbear.manjuan.source.local` | SAF file and folder import on the Add screen |
| `com.numbear.manjuan.source.webdav` | Account form, connection test, remote browse, add |
| `com.numbear.manjuan.cache` | Whole-book cache progress |
| `com.numbear.manjuan.reader.text` | Novel reader |
| `com.numbear.manjuan.reader.pdf` | PDF page reader |
| `com.numbear.manjuan.reader.comic` | Comic reader |
| `com.numbear.manjuan.progress` | Progress slider, bookmarks, jump percent |
| `com.numbear.manjuan.settings` | Defaults: theme, type, comics, keys, orientation, screen-on |

Shared reader chrome lives in `com.numbear.manjuan.reader.common`.

## Formats

Detection uses the file extension and magic bytes. ZIP-based books also look at entry names and the EPUB `mimetype` entry when a probe is available.

| Format | Recognition | Reader |
| --- | --- | --- |
| TXT | `.txt`, or text that is not a known binary | Novel |
| EPUB | `.epub` + `PK` header, or ZIP `mimetype` of `application/epub+zip` | Novel (HTML stripped to text) |
| MOBI / AZW3 | `.mobi` / `.azw` / `.azw3` and `BOOKMOBI` at offset 60 | Novel, PalmDOC none or LZ77. Huff/CDIC and DRM return a clear error |
| PDF | `.pdf` + `%PDF` | Page bitmaps via `PdfRenderer` (novels and scan comics) |
| CBZ | `.cbz` + `PK`, image entries | Comic |
| ZIP of images | `.zip` + `PK` + image entries | Comic |
| CBR | `.cbr` + `Rar!` | Comic via Junrar. Unsupported RAR versions return a clear error |
| Image folder | SAF tree whose files are images | Comic, read through the persisted tree permission |

Anything else, or an extension that disagrees with the magic bytes, is rejected with a Chinese message. No silent skip.

Image extensions: jpg, jpeg, png, webp, gif, bmp.

## Screens

1. **Bookshelf.** Chips: 最近, 全部, 按来源. Search matches title and author. Each row shows a generated cover, format, source, and percent. Long-press deletes. Empty state points at Add.
2. **Add.** Local: system document picker (multiple files) and folder tree. WebDAV: display name, URL, username, password, root path, test connection, save, then browse. Saved accounts can be opened again.
3. **WebDAV browse.** Directories drill in. A file can be added to the shelf or cached in full. A directory of images can be added as one comic.
4. **Novel reader.** Page-turn or vertical scroll. Tap left / center / right. Font size, line spacing, margins. Day, night, sepia, custom swatches. TOC, bookmarks, percent slider, jump to percent. Optional volume keys. Optional keep-screen-on. Restores the last locator.
5. **Comic reader.** Vertical, RTL, or LTR. Single page, or dual page in landscape when enabled. Fit width, fit height, fit page, original. Pinch zoom. Edge tap. Optional volume keys. Progress and bookmarks.
6. **PDF reader.** Same page chrome as comics (direction, fit, zoom, bookmarks) because a PDF may be a novel or a scan.
7. **Settings.** The defaults above, plus orientation follow / portrait / landscape, plus delete WebDAV account.

## Reading behavior

- Novel locator: `c={chapterIndex};o={charOffset}`. Percent is global character offset divided by total characters.
- Comic and PDF locator: `p={pageIndex}`. Percent is page index divided by page count.
- Page-turn pagination is measured from font size, spacing, margins, and the available box. A newline ends a line early. Chinese glyphs are treated as one full em.
- Scroll mode shows the current chapter in a vertical scroller and restores an approximate character offset.
- Volume keys: previous on volume up, next on volume down, only when the preference is on. Handled in the Activity so the system does not swallow them.
- Orientation is applied from settings on the Activity.

## Data

Room database `manjuan.db` (schema version 1, export off):

- `sources` — `LOCAL` or `WEBDAV`. WebDAV rows store base URL, username, encrypted password, and root path.
- `books` — source id, title, author, format, kind (`NOVEL`, `COMIC`, `PDF`), `location`, `remotePath`, `cachedPath`, timestamps, size.
- `progress` — one row per book: locator, percent, updatedAt.
- `bookmarks` — book id, locator, label, createdAt. Deleting a book cascades progress and bookmarks. Deleting a source cascades its books.

Local files selected through SAF are copied into app storage so ZIP, PDF, and MOBI can be opened with random access after the picker returns. Image folders are not copied; the persisted tree URI and document id are stored.

WebDAV passwords are encrypted with an AES-GCM key that never leaves the Android Keystore (alias `manjuan.webdav.aes`). The Room column holds Base64(IV + ciphertext). Empty passwords stay empty.

Progress merge: adding a WebDAV path that already exists on that source reuses the row. Caching updates `cachedPath` on that same row, so the existing locator remains. If two rows ever share a source and remote path, `ProgressMerge` keeps the progress with the later `updatedAt`, and the higher percent when timestamps are equal, then the duplicate row is dropped.

## WebDAV and cache

Minimal client (PROPFIND Depth 1, GET, optional Range for magic bytes):

- Test connection: PROPFIND the root. HTTP 401/403 → 「账号或密码不正确」. Transport failure → 「无法连接服务器」. Other non-success codes include the HTTP status.
- Browse: one level, skip the collection href itself, directories first, then natural-sorted names.
- Add: store the remote path and detected format. The file is not downloaded yet.
- Cache whole book: download to `filesDir/cache-books/{bookId}/`. A remote image directory downloads each child image. The UI shows read/total bytes when the server sends a length.
- Open: if `cachedPath` or a local file exists, open it. If the book is remote and uncached, download it (same as cache) and then open. If that download fails, show 「未缓存，当前无法离线打开」.

Cleartext HTTP is allowed so a LAN NAS without TLS still works.

## Errors

User-visible failures are short Chinese sentences: bad magic, encrypted MOBI, Huff/CDIC MOBI, unreadable EPUB, unsupported CBR, missing cache while offline, WebDAV auth failure. Parsers throw `UnsupportedBookException` with that sentence. The UI shows it in a dialog or a reader error state. They do not crash the process.

## Out of scope

iOS, cloud sync, TTS, online bookstore, site scraping, OPDS, dictionary, comments, account system, page curl animation, reflow of PDF text, DRM.

## Verification

- `:core` unit tests cover detection, encoding, chapters, pagination, HTML strip, a synthetic EPUB, a synthetic MOBI, PalmDOC LZ77, progress merge, natural sort, PROPFIND parsing, and a MockWebServer PROPFIND + GET.
- `./gradlew :app:assembleDebug` produces the debug APK.
- Instrumented UI tests are not part of v1. There is no emulator in the build environment.

## Delivery slices

1. Gradle project, Compose shell, Room models, this spec, Chinese README.
2. Local TXT and EPUB into the novel reader.
3. CBZ and image folders into the comic reader.
4. PDF.
5. WebDAV browse and add.
6. Whole-book cache and offline open.
7. MOBI/AZW3, CBR, and the reading-UX defaults (themes, tap zones, bookmarks, keys, orientation).
