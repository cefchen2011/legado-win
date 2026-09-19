// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 BookHelp（21KB）负责本地书籍的目录/封面/章节缓存文件管理，
// 依赖 Android 的 SAF、ParcelFileDescriptor、Bitmap 与 localBook 扫描。
// 桌面端文件系统是直接可访问的，因此这里只实现规则引擎用到的部分：
//   getChapterFiles(book) —— 列出该书已缓存的章节文件名
package io.legado.app.help.book

import io.legado.app.constant.AppPattern
import io.legado.app.data.DesktopPaths
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.LogUtils
import io.legado.app.utils.NetworkUtils
import java.io.File

object BookHelp {

    private const val TAG = "BookHelp"

    /** 正文缓存目录名（与上游一致） */
    const val cacheFolderName = "book_cache"

    val downloadDir: File
        get() = File(DesktopPaths.filesDir, "download").apply { mkdirs() }

    /**
     * 上游签名：返回该书已下载/缓存的章节文件名集合。
     * 桌面端直接读取本地缓存目录。
     */
    fun getChapterFiles(book: Book): HashSet<String> {
        val fileNames = hashSetOf<String>()
        if (book.isLocalTxt) return fileNames
        runCatching {
            val dir = File(downloadDir, "$cacheFolderName/${book.getFolderName()}")
            dir.list()?.let { fileNames.addAll(it) }
        }.onFailure {
            LogUtils.e(TAG, "读取章节缓存目录失败: ${it.message}")
        }
        return fileNames
    }

    /** 上游：清理书名中的非法/冗余字符 */
    fun formatBookName(name: String): String {
        return name
            .replace(AppPattern.nameRegex, "")
            .trim { it <= ' ' }
    }

    /** 上游：清理作者名 */
    fun formatBookAuthor(author: String): String {
        return author
            .replace(AppPattern.authorRegex, "")
            .trim { it <= ' ' }
    }

    /** 上游签名：把本地书以 ParcelFileDescriptor 打开（epublib 需要） */
    fun getBookPFD(book: Book): android.os.ParcelFileDescriptor? =
        io.legado.app.model.localBook.LocalBook.getBookPFD(book)

    /**
     * 从章节正文里抽出图片地址（漫画阅读用）。
     *
     * 与上游 `BookHelp.flowImages` 同一实现：用 `AppPattern.imgPattern` 扫 `<img src>`，
     * 相对地址按章节 url 解析成绝对地址。桌面端直接返回 List，语义一致。
     */
    fun flowImages(bookChapter: BookChapter, content: String): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            val matcher = AppPattern.imgPattern.matcher(content)
            while (matcher.find()) {
                val src = matcher.group(1) ?: continue
                result.add(NetworkUtils.getAbsoluteURL(bookChapter.url, src))
            }
        }.onFailure {
            LogUtils.e(TAG, "解析章节图片失败: ${it.message}")
        }
        return result
    }

    /**
     * 上游把正文写入缓存文件（供离线阅读与导出）。
     * 桌面端直接写本地文件系统。
     */
    fun saveContent(
        bookSource: io.legado.app.data.entities.BaseSource?,
        book: Book,
        bookChapter: BookChapter,
        content: String?
    ) {
        if (content.isNullOrEmpty()) return
        runCatching {
            val file = File(downloadDir, "$cacheFolderName/${book.getFolderName()}/${bookChapter.getFileName()}")
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }.onFailure {
            LogUtils.e(TAG, "保存正文失败: ${it.message}")
        }
    }

    /**
     * 上游：书源/目录变化后重新定位阅读进度（按章节名相似度对齐）。
     * 桌面端实现同样的两步策略：先按章节名精确匹配，再按比例回退。
     */
    fun getDurChapter(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0
    ): Int {
        if (oldDurChapterIndex <= 0) return 0
        if (newChapterList.isEmpty()) return oldDurChapterIndex

        // 1) 章节标题仍在 -> 直接对齐（去掉首尾空白后比较，兼容标题微调）
        if (!oldDurChapterName.isNullOrBlank()) {
            val pure = oldDurChapterName.trim()
            val exact = newChapterList.indexOfFirst { it.title?.trim() == pure }
            if (exact >= 0) return exact
            // 标题包含关系：处理"第12章 xxx"与"12. xxx"这类差异
            val loose = newChapterList.indexOfFirst {
                val t = it.title?.trim() ?: return@indexOfFirst false
                t.isNotEmpty() && (t.contains(pure) || pure.contains(t))
            }
            if (loose >= 0) return loose
        }

        // 2) 回退：按旧列表长度比例换算，再夹到合法范围
        val newSize = newChapterList.size
        val durIndex =
            if (oldChapterListSize <= 0) oldDurChapterIndex
            else oldDurChapterIndex * oldChapterListSize / newSize
        return durIndex.coerceIn(0, newSize - 1)
    }
}
