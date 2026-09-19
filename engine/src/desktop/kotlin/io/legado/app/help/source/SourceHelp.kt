// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 SourceHelp 通过 Intent 启动 VideoPlayService（悬浮窗/全屏播放）。
// 桌面端没有 Service，视频播放交由应用外壳承接，因此这里保留同名同签名的
// 公开 API，行为收敛为日志 + 回调。
package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.utils.LogUtils

object SourceHelp {

    private const val TAG = "SourceHelp"

    /** 桌面端由应用外壳弹出播放窗口；引擎侧只登记请求。 */
    var videoPlayerLauncher: ((url: String, title: String, sourceKey: String?) -> Unit)? = null

    fun openVideoPlayer(source: BaseSource?, url: String, title: String, isFloat: Boolean) {
        val key = source?.getKey()
        val launcher = videoPlayerLauncher
        if (launcher != null) {
            launcher(url, title, key)
        } else {
            LogUtils.i(TAG, "请求播放视频（桌面端暂无播放器）：$title $url isFloat=$isFloat")
        }
    }
}
