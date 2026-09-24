# 漫卷

安卓小说 / 漫画阅读器。本地文件用系统选择器读入，WebDAV 书库可以浏览、加入书架，并整本缓存后离线打开。

包名：`com.numbear.manjuan`  
技术：Kotlin、Jetpack Compose、Room。不是 Flutter，也不是套一层网页。

## 环境

- JDK 17 或更高
- Android SDK，已安装 `platforms;android-36` 和 `build-tools;36.0.0`
- 环境变量 `ANDROID_HOME` 指向 SDK 根目录

```bash
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

最低系统 Android 8.0（API 26）。编译和目标版本是当前稳定的 Android 16（API 36）。

## 编译

```bash
./gradlew :app:assembleDebug
```

调试 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

不想在本机装 SDK 时，可以直接下载 CI 打出来的调试包（仓库内固定 CI 密钥签名，侧载安装）。更早的随机调试签名包无法覆盖安装，需要先卸载一次：

https://github.com/NumbEarLILI/manjuan/releases/tag/manjuan-apk-20260923

发布页上的 `manjuan.apk` 显示为「漫卷.apk」，旁边还有 `manjuan-debug.apk`。同一构建也会以名为 `manjuan-apk` 的 Actions 产物保留 30 天。推送到 `main` 会重新编译并更新这份预发布。

核心解析（编码、EPUB、MOBI、WebDAV）可以用 JVM 单测，不需要模拟器：

```bash
./gradlew :core:test
```

## 本地导入

1. 打开书架，点右下角加号。
2. 「选择文件」可以一次选多本：TXT、Markdown、EPUB、MOBI、AZW3、PDF、CBZ、CBR、图片 ZIP。
3. 「选择文件夹」会导入其中的书籍；如果某一层只有图片，会当成一本漫画。
4. 扩展名和文件头对不上，或压缩包里没有可看的内容时，会直接说明原因，不会静默跳过。
5. 点进书即阅读。小说可翻页或纵向滚动，漫画可纵向 / 从右向左 / 从左向右。进度和书签记在本机。

TXT 会识别 UTF-8、UTF-16 和 GB18030。EPUB 转成正文用 Compose 排版，不用网页控件。

## WebDAV

1. 添加页填写显示名称、服务器地址（以 `http://` 或 `https://` 开头）、用户名、密码、根路径。
2. 「测试连接」成功后再「保存并浏览」。密码用 Android Keystore 的 AES-GCM 加密后写入 Room。
3. 点目录进入下一层。文件可以「加入书架」（先不下载）或「缓存整本」。
4. 图片目录可以整夹加入，缓存时会把图片下到应用目录。
5. 打开尚未缓存的远程书时会尝试下载；没有网络会提示「未缓存，当前无法离线打开」。已经缓存的书不需要再连服务器。
6. 同一账号下相同远程路径不会重复建档，原有进度保留。设置页可以删除 WebDAV 账号。

局域网没有证书的 HTTP 地址也可以用。

## 模块

| Gradle | 内容 |
| --- | --- |
| `:core` | 格式识别、编码、EPUB、MOBI、WebDAV、进度合并 |
| `:data` | Room、密码加密、SAF 导入、整本缓存 |
| `:app` | 界面 |

界面按功能分包：`bookshelf`、`source.local`、`source.webdav`、`cache`、`reader.text`、`reader.pdf`、`reader.comic`、`progress`（阅读进度和书签在阅读器里）、`settings`。

设计说明：`docs/superpowers/specs/2026-09-23-novel-comic-reader-design.md`

## 这一版没有

iOS、云同步、朗读、在线书城、网页抓取、OPDS。MOBI 的 Huff/CDIC 和加密本、RAR5 的 CBR 会提示暂不支持。PDF 按页渲染，不抽取正文重排。
