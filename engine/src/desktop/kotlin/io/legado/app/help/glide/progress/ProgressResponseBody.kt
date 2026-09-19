// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游用 Glide 的下载进度回调驱动书架进度条。桌面端改为把进度交给应用外壳，
// 这里保留上游的包路径与类名，使 HttpHelper 的 import 零改动。
package io.legado.app.help.glide.progress

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** 下载进度监听 */
fun interface ProgressListener {
    fun update(bytesRead: Long, contentLength: Long, done: Boolean)
}

object ProgressManager {

    /** 上游用全局单例监听器把进度推给 UI；桌面端交给应用外壳订阅 */
    @JvmField
    val LISTENER: ProgressListener = ProgressListener { _, _, _ ->
        // 由应用外壳按需订阅，引擎侧不额外处理
    }
}

/**
 * 包装 ResponseBody，在读取过程中回调进度。行为与上游一致。
 */
class ProgressResponseBody(
    private val url: String,
    private val listener: ProgressListener,
    private val responseBody: ResponseBody?,
) : ResponseBody() {

    private val bufferedSource: BufferedSource by lazy {
        val body = responseBody!!
        val contentLength = body.contentLength()
        val forwarding = object : ForwardingSource(body.source()) {
            var totalBytesRead = 0L

            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                listener.update(
                    totalBytesRead,
                    if (contentLength > 0) contentLength else totalBytesRead,
                    bytesRead == -1L
                )
                return bytesRead
            }
        }
        forwarding.buffer()
    }

    override fun contentType(): MediaType? = responseBody?.contentType()

    override fun contentLength(): Long = responseBody?.contentLength() ?: -1L

    override fun source(): BufferedSource = bufferedSource

    fun url(): String = url
}
