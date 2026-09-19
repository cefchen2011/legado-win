// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 SourceVerificationHelp 用于"图片验证码 / 防爬 / 滑块 / 点击字符"这类
// 需要用户介入的场景：启动 VerificationCodeActivity 或内置 WebViewActivity，
// 然后 LockSupport.park 阻塞后台线程，等待用户提交结果。
//
// 桌面端没有 Activity，改为**把请求交给应用外壳**（可以弹窗或开网页），
// 同样阻塞等待结果回填。公开 API 与上游同名同签名，JsExtensions 调用点零改动。
package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.LogUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SourceVerificationHelp {

    private const val TAG = "SourceVerification"

    /** 等待用户输入的轮询间隔与超时（桌面端必须有上限，避免永久挂起） */
    private const val WAIT_STEP_MS = 100L
    private const val WAIT_TIMEOUT_MS = 5 * 60 * 1000L

    /** 请求应用外壳展示验证界面：参数为 (url, title, sourceKey, useBrowser, html) */
    var verificationUiLauncher: ((url: String, title: String, sourceKey: String, useBrowser: Boolean, html: String?) -> Unit)? =
        null

    private val resultMap = ConcurrentHashMap<String, Pair<String, String>>()
    private val latches = ConcurrentHashMap<String, CountDownLatch>()

    private fun getVerificationResultKey(source: BaseSource) = getVerificationResultKey(source.getKey())

    private fun getVerificationResultKey(sourceKey: String) = "${sourceKey}_verificationResult"

    /**
     * 获取书源验证结果。桌面端阻塞等待应用外壳回填 [setResult]。
     */
    @Synchronized
    fun getVerificationResult(
        source: BaseSource?,
        url: String,
        title: String,
        useBrowser: Boolean,
        refetchAfterSuccess: Boolean = true,
        html: String? = null
    ): Pair<String, String> {
        source ?: throw NoStackTraceException("getVerificationResult parameter source cannot be null")
        require(url.length < 64 * 1024) { "getVerificationResult parameter url too long" }

        clearResult(source.getKey())

        if (!useBrowser) {
            LogUtils.i(TAG, "请求验证码输入：$title $url")
        } else {
            startBrowser(source, url, title, true, refetchAfterSuccess, html)
        }

        val key = getVerificationResultKey(source)
        val latch = CountDownLatch(1)
        latches[key] = latch
        val ok = latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        latches.remove(key)

        if (!ok) {
            LogUtils.w(TAG, "等待验证结果超时：$title")
        }
        val result = resultMap.remove(key) ?: throw NoStackTraceException("验证结果为空")
        if (result.second.isEmpty()) throw NoStackTraceException("验证结果为空")
        return result
    }

    /**
     * 启动内置浏览器。桌面端交由应用外壳开窗口 / 系统浏览器。
     */
    fun startBrowser(
        source: BaseSource?,
        url: String,
        title: String,
        saveResult: Boolean? = false,
        refetchAfterSuccess: Boolean? = true,
        html: String? = null
    ) {
        source ?: throw NoStackTraceException("startBrowser parameter source cannot be null")
        require(url.length < 64 * 1024) { "startBrowser parameter url too long" }
        val key = getVerificationResultKey(source)
        latches.computeIfAbsent(key) { CountDownLatch(1) }
        verificationUiLauncher?.invoke(url, title, source.getKey(), true, html)
            ?: LogUtils.i(TAG, "请求打开浏览器：$title $url")
    }

    /** 应用外壳在用户完成验证后调用，回填结果并唤醒等待线程。 */
    fun setResult(sourceKey: String, result: Pair<String, String>) {
        val key = getVerificationResultKey(sourceKey)
        resultMap[key] = result
        latches[key]?.countDown()
        latches.remove(key)
    }

    fun getResult(sourceKey: String): Pair<String, String>? =
        resultMap[getVerificationResultKey(sourceKey)]

    fun clearResult(sourceKey: String) {
        val key = getVerificationResultKey(sourceKey)
        resultMap.remove(key)
    }

    /** 上游由界面调用以解除阻塞；桌面端等价实现。 */
    fun checkResult(sourceKey: String) {
        if (getResult(sourceKey) == null) setResult(sourceKey, "" to "")
    }
}
