// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游的 ReadBook / AudioPlay / ReadManga 是 Android 上的运行时状态机：
//   ReadBook  阅读会话（当前书、当前章、翻页、朗读）
//   AudioPlay 在线朗读播放器
//   ReadManga 漫画阅读器（含限速器）
// 桌面端这些状态由应用外壳持有，引擎只需要其中的**静态访问点**，
// 因此这里保留同名同类型的成员，行为收敛为内存状态。
package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.help.ConcurrentRateLimiter

/** 阅读会话：引擎侧只用它的"当前书"引用。 */
object ReadBook {

    @Volatile
    var book: Book? = null
        set(value) {
            field = value
        }
}

/** 在线朗读：桌面端由应用外壳实现播放，这里只保留状态占位。 */
object AudioPlay {

    @Volatile
    var isPlaying: Boolean = false

    @Volatile
    var book: Book? = null

    fun stop() {
        isPlaying = false
    }
}

/** 漫画阅读：桌面端暂不实现漫画，保留限速器以满足调用点。 */
object ReadManga {

    /** 与上游一致：漫画图片抓取的限速器 */
    val rateLimiter = ConcurrentRateLimiter(null)

    @Volatile
    var book: Book? = null
}
