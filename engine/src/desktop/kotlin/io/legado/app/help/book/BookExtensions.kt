// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 BookExtensions.kt（11KB）里大量扩展依赖 Android 的 SAF/DocumentFile、
// 封面 Bitmap、本地书籍扫描（help.localBook），这些在桌面端由应用外壳重写。
// 这里提供**规则引擎真正需要**的那部分扩展，签名与上游一致。
package io.legado.app.help.book

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.utils.MD5Utils
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 上游按位判断书籍类型（单类型，签名与上游一致） */
fun Book.isType(@BookType.Type bookType: Int): Boolean = type and bookType > 0

fun Book.setType(@BookType.Type vararg types: Int) {
    type = 0
    addType(*types)
}

fun Book.addType(@BookType.Type vararg types: Int) {
    types.forEach {
        type = type or it
    }
}

fun Book.removeType(@BookType.Type vararg types: Int) {
    types.forEach {
        type = type and it.inv()
    }
}

fun Book.removeAllBookType() {
    removeType(BookType.allBookType)
}

fun Book.clearType() {
    type = 0
}

val Book.isImage: Boolean
    get() = isType(BookType.image)

val Book.isVideo: Boolean
    get() = isType(BookType.video)

val Book.isAudio: Boolean
    get() = isType(BookType.audio)

val Book.isText: Boolean
    get() = isType(BookType.text)

val Book.isWebFile: Boolean
    get() = isType(BookType.webFile)

val Book.isUpError: Boolean
    get() = isType(BookType.updateError)

/** 在线文本（非本地） */
val Book.isOnLineTxt: Boolean
    get() = !isLocal && isType(BookType.text)

/** 本地书籍（含 WebDAV/远程本地书） */
val Book.isLocal: Boolean
    get() {
        if (type == 0) {
            return origin == BookType.localTag || origin.startsWith(BookType.webDavTag)
        }
        return isType(BookType.local)
    }

val Book.isLocalTxt: Boolean
    get() = isLocal && originName.endsWith(".txt", true)

/** 本地文件是否被外部改动过（上游据此重建目录与正文缓存） */
fun Book.isLocalModified(): Boolean {
    return isLocal &&
        io.legado.app.model.localBook.LocalBook.getLastModified(this)
            .getOrDefault(0L) > latestChapterTime
}

val Book.isEpub: Boolean
    get() = isLocal && originName.endsWith(".epub", true)

val Book.isUmd: Boolean
    get() = isLocal && originName.endsWith(".umd", true)

val Book.isPdf: Boolean
    get() = isLocal && originName.endsWith(".pdf", true)

/** 缓存目录名：书名（去掉非法字符，截断 9 字）+ 书址 MD5 前 16 位 */
fun Book.getFolderNameNoCache(): String {
    return name.replace(AppPattern.fileNameRegex, "").let {
        it.substring(0, minOf(9, it.length)) + MD5Utils.md5Encode16(bookUrl)
    }
}

fun Book.getFolderName(): String = getFolderNameNoCache()

/**
 * 模拟阅读：按起始日期与每日解锁章数推算当前应读到的章节数。
 * 上游实现依赖 Android 的日期工具；桌面端用 java.time 等价实现。
 */
fun Book.simulatedTotalChapterNum(): Int {
    if (!getReadSimulating()) return 0
    val start = config.startDate ?: return 0
    val daysPassed = ChronoUnit.DAYS.between(start, LocalDate.now()).toInt() + 1
    val daily = config.dailyChapters
    val startChapter = config.startChapter ?: 0
    if (daily <= 0) return startChapter
    return maxOf(0, startChapter + daysPassed * daily)
}
