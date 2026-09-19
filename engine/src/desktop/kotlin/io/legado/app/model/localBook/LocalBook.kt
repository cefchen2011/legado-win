// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 LocalBook 通过 Android 的 SAF（DocumentFile / ContentResolver）读取本地书籍，
// 并且要处理 "内容" 类型的 uri 与真实路径两套逻辑。
//
// 桌面端文件系统可直接访问，因此这里只需把 bookUrl 还原成文件路径即可：
//   * 支持普通绝对路径，如 D:\books\xxx.txt
//   * 支持 file:///D:/books/xxx.txt 形式
//   * 支持 webDav:: 前缀（legado 的远程本地书标记）——桌面端取其后的路径
package io.legado.app.model.localBook

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isLocal
import io.legado.app.utils.LogUtils
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

object LocalBook {

    private const val TAG = "LocalBook"

    /** 把 legado 的 bookUrl 还原成本地文件 */
    fun fileOf(book: Book): File? {
        val raw = book.bookUrl.ifBlank { return null }
        val path = when {
            raw.startsWith(BookType.webDavTag) -> raw.removePrefix(BookType.webDavTag)
            raw.startsWith("file:///") ->
                // file:///D:/x.txt -> D:/x.txt
                java.net.URLDecoder.decode(raw.removePrefix("file:///"), "UTF-8")

            raw.startsWith("file://") ->
                java.net.URLDecoder.decode(raw.removePrefix("file://"), "UTF-8")

            else -> raw
        }
        return File(path)
    }

    /** 上游签名：拿书籍内容的输入流 */
    fun getBookInputStream(book: Book): InputStream {
        val file = fileOf(book)
            ?: throw java.io.FileNotFoundException("无法解析书籍路径: ${book.bookUrl}")
        if (!file.exists()) {
            throw java.io.FileNotFoundException("文件不存在: ${file.absolutePath}")
        }
        return FileInputStream(file)
    }

    /** 上游签名：文件最后修改时间（用于判断本地书是否被外部改动过） */
    fun getLastModified(book: Book): Result<Long> = runCatching {
        fileOf(book)?.lastModified() ?: 0L
    }

    /** 上游签名：文件长度 */
    fun getLength(book: Book): Result<Long> = runCatching {
        fileOf(book)?.length() ?: 0L
    }

    /** 上游签名：与 BookHelp.getBookPFD 配套的 PFD 获取 */
    fun getBookPFD(book: Book): android.os.ParcelFileDescriptor? = runCatching {
        val f = fileOf(book) ?: return@runCatching null
        if (!f.exists()) return@runCatching null
        android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }.getOrNull()

    /**
     * 上游签名：封面图缓存路径。
     * 桌面端把封面统一放在 cache/cover/<md5>.jpg，由 EpubFile 写入后由前端直接引用。
     */
    fun getCoverPath(book: Book): String {
        val dir = File(io.legado.app.data.DesktopPaths.cacheDir, "cover").apply { mkdirs() }
        val name = io.legado.app.utils.MD5Utils.md5Encode16(book.bookUrl) + ".jpg"
        return File(dir, name).absolutePath
    }

    fun exists(book: Book): Boolean = fileOf(book)?.exists() == true

    /**
     * 把本地文件登记成一本"本地书"。
     * bookUrl 直接用绝对路径，origin 用 legado 的本地标记，这样
     * `Book.isLocal` 等上游扩展能正确识别。
     */
    fun createBook(file: File, charset: String? = null): Book {
        val name = file.nameWithoutExtension
        return Book().apply {
            this.name = name
            this.author = ""
            this.bookUrl = file.absolutePath
            this.origin = BookType.localTag
            this.originName = file.name
            // 必须带上 local 位：上游的 isLocal / isEpub / isLocalTxt 都依赖它，
            // 少了这一位会被当成在线书去走书源规则
            this.type = BookType.local or BookType.text
            this.charset = charset
            this.latestChapterTime = file.lastModified()
        }
    }

    /** 让上游的 isLocal 判断成立：这里保留一个显式入口便于调试 */
    fun describe(book: Book): String =
        "book=${book.name} local=${book.isLocal} file=${fileOf(book)?.absolutePath}"
}
