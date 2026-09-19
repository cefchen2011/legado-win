// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 ExoPlayerHelper 基于 androidx.media3 的 ExoPlayer 构建播放器；
// 桌面端音频播放将由应用外壳以系统播放器/Web Audio 承接，引擎侧只需要
// 「把 url + 请求头打包成 MediaItem」这一能力，因此这里保留该函数。
package io.legado.app.help.exoplayer

import android.content.Context
import androidx.media3.common.MediaItem
import com.google.gson.reflect.TypeToken
import io.legado.app.utils.GSON

private const val SPLIT_TAG = "\uD83D\uDEA7"

object ExoPlayerHelper {

    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    /** 与上游一致：url 与请求头 JSON 用分隔符拼接后作为 MediaItem 的 uri。 */
    fun createMediaItem(url: String, headers: Map<String, String>): MediaItem {
        val formatUrl = url + SPLIT_TAG + GSON.toJson(headers, mapType)
        return MediaItem.Builder().setUri(formatUrl).build()
    }

    /** 上游返回 ExoPlayer；桌面端不构建播放器，保留签名以便上层调用点不变。 */
    fun createHttpExoPlayer(context: Context): Nothing =
        throw UnsupportedOperationException("桌面端音频播放由应用外壳实现，引擎不再构建 ExoPlayer")
}
