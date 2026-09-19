// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 Debug 面向 UI：通过 Callback 把调试信息推给调试界面，并用协程驱动书源调试。
// 桌面端第一阶段把调试输出收敛到日志，保留同名同签名的公开 API，
// 使 AnalyzeRule / AnalyzeUrl 等核心文件里的 `Debug.log(...)` 零改动。
package io.legado.app.model

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

object Debug {

    private const val TAG = "Debug"

    /** 上游把调试信息推给 UI；桌面端由应用外壳订阅。 */
    interface Callback {
        fun onDebugMessage(sourceUrl: String?, msg: String?)
    }

    var callback: Callback? = null

    val debugMessageMap = ConcurrentHashMap<String, String>()

    var isChecking: Boolean = false

    private val debugTimeMap = ConcurrentHashMap<String, Long>()

    // ---------------------------------------------------------------- 输出
    /**
     * 与上游同签名：`log(sourceUrl, msg, print, isHtml, showTime, state)`。
     * state 用于调试界面区分信息级别，桌面端只体现在日志前缀上。
     */
    fun log(
        sourceUrl: String?,
        msg: String = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) {
        val key = sourceUrl ?: ""
        val shown = if (isHtml) "(html) ${msg.length} 字符" else msg
        debugMessageMap[key] = msg
        if (print) {
            LogUtils.d(TAG, "[state=$state][$key] $shown")
        }
        callback?.onDebugMessage(sourceUrl, msg)
    }

    fun log(msg: String?) {
        log(null, msg ?: "", true)
    }

    // ---------------------------------------------------------------- 书源调试
    fun startChecking(source: BookSource) {
        isChecking = true
        debugTimeMap[source.bookSourceUrl] = System.currentTimeMillis()
        log(source.bookSourceUrl, "开始校验")
    }

    fun finishChecking() {
        isChecking = false
    }

    fun cancelDebug(destroy: Boolean = false) {
        isChecking = false
        debugMessageMap.clear()
        debugTimeMap.clear()
    }

    fun getRespondTime(sourceUrl: String): Long {
        val start = debugTimeMap[sourceUrl] ?: return 0L
        return System.currentTimeMillis() - start
    }

    fun updateFinalMessage(sourceUrl: String, state: String) {
        log(sourceUrl, state)
    }

    /** 上游用协程调试订阅源；桌面端暂时只记录日志。 */
    fun startDebug(scope: CoroutineScope, rssSource: RssSource, key: String) {
        LogUtils.w(TAG, "桌面端暂未实现订阅源调试：$key")
    }
}
