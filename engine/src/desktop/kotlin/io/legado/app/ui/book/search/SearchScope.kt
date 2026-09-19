// [desktop-port] 桌面端替代实现，非上游源码。
//
// 一组 Android 专属设施的最小桌面等价物，集中放在这里便于查阅：
//   SearchScope        搜索范围（上游基于 LiveData 的 UI 状态）
//   exploreInfoMapList 发现页书源分组缓存
//   CrashHandler       崩溃处理（上游写崩溃日志到应用目录）
//   CanvasRecorderFactory / Cronet / CharsetDetector / Glide 桩
//   ReadBook / AudioPlay / ReadManga 模型桩
package io.legado.app.ui.book.search

import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook

/**
 * 搜索范围。上游用 MutableLiveData 做 UI 状态；桌面端由应用外壳管理状态，
 * 这里保留同名的数据模型与查询方法。
 */
class SearchScope {

    var books: List<SearchBook> = emptyList()
        private set

    /**
     * 内部字段用 sourceParts 命名：上游调用点使用 `getBookSourceParts()`，
     * 若属性也叫 bookSourceParts 会与该方法的 JVM 签名冲突。
     */
    private var sourceParts: List<BookSourcePart> = emptyList()

    var isSearching: Boolean = false

    /** 按书源分组后的搜索结果，对应上游 UI 的"按源查看" */
    val grouped: Map<String, List<SearchBook>>
        get() = books.groupBy { it.origin }

    fun update(books: List<SearchBook>, parts: List<BookSourcePart>) {
        this.books = books
        this.sourceParts = parts
    }

    /** 上游由 SourceCallBack.getSearchScope() 提供：当前参与搜索的书源列表 */
    fun getBookSourceParts(): List<BookSourcePart> = sourceParts

    fun setBookSourceParts(parts: List<BookSourcePart>) {
        this.sourceParts = parts
    }

    fun clear() {
        books = emptyList()
        sourceParts = emptyList()
        isSearching = false
    }
}
