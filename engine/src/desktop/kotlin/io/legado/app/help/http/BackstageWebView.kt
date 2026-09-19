// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 BackstageWebView 基于 Android WebView：加载页面、注入脚本、等渲染完成后取回 HTML。
// 桌面端没有 WebView，第一阶段**降级为直连抓取**：
//   * 已有 html 内容 -> 直接返回（注入脚本无法执行，只记录告警）
//   * 只有 url      -> 用 OkHttp 拉取原始 HTML
//
// 这与 legado 在"书源不需要 WebView"时的行为一致。对少数必须执行 JS 的书源，
// 后续可在此接入无头浏览器（WebView2），上层调用点不受影响。
//
// 构造参数与上游 BackstageWebView 完全对齐（含 HashMap 与 suspend 语义）。
package io.legado.app.help.http

import io.legado.app.utils.LogUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class BackstageWebView(
    private val url: String? = null,
    private val html: String? = null,
    private val encode: String? = null,
    private val tag: String? = null,
    private val headerMap: HashMap<String, String>? = null,
    private val sourceRegex: String? = null,
    private val overrideUrlRegex: String? = null,
    private val javaScript: String? = null,
    private var delayTime: Long = 0,
    private val cacheFirst: Boolean = false,
    private val timeout: Long? = null,
    private val result: String? = null,
    private val isRule: Boolean = false
) {

    /**
     * 上游是 suspend（内部 withTimeout 等待 WebView 渲染）；
     * 桌面端直连抓取本身是阻塞调用，保持 suspend 以满足调用点语义。
     */
    suspend fun getStrResponse(): StrResponse {
        if (!html.isNullOrEmpty()) {
            if (!javaScript.isNullOrEmpty()) {
                LogUtils.w(TAG, "桌面端暂不支持在 WebView 中执行注入脚本，已忽略 js")
            }
            if (isRule && !result.isNullOrEmpty()) {
                LogUtils.d(TAG, "规则提取结果: $result")
            }
            return StrResponse(url ?: "", html)
        }

        val target = url ?: return StrResponse("", null)
        val effectiveTimeout = timeout ?: 60_000L
        return runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build()
            val builder = Request.Builder().url(target)
            headerMap?.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                StrResponse(resp, resp.body?.string())
            }
        }.getOrElse { e ->
            LogUtils.e(TAG, "后台抓取失败 $target: ${e.message}")
            StrResponse(target, null)
        }
    }

    companion object {
        private const val TAG = "BackstageWebView"

        /** 上游用于释放 WebView 池；桌面端无资源可释放。 */
        fun destroy() {
            // no-op
        }
    }
}
