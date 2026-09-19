// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 AppConst 依赖 Android 的 PackageManager / Settings.Secure / Material 尺寸资源。
// 桌面端保留全部公开成员与类型，把三处 Android 取值换成桌面等价物：
//   sysElevation —— Material 的 appbar 阴影高度，取固定值
//   androidId    —— 设备标识，改为首次运行生成并持久化的 UUID
//   appInfo      —— 版本信息，改由 BuildConfig 提供
package io.legado.app.constant

import android.annotation.SuppressLint
import androidx.annotation.Keep
import io.legado.app.BuildConfig
import io.legado.app.help.update.AppVariant
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import org.apache.commons.lang3.time.FastDateFormat
import splitties.init.appCtx
import java.util.UUID

@Suppress("ConstPropertyName")
@SuppressLint("SimpleDateFormat")
object AppConst {

    const val APP_TAG = "Legado"

    const val channelIdDownload = "channel_download"
    const val channelIdReadAloud = "channel_read_aloud"
    const val channelIdWeb = "channel_web"

    const val UA_NAME = "User-Agent"

    const val MAX_THREAD = 9

    const val DEFAULT_WEBDAV_ID = -1L

    val timeFormat: FastDateFormat by lazy {
        FastDateFormat.getInstance("HH:mm")
    }

    val dateFormat: FastDateFormat by lazy {
        FastDateFormat.getInstance("yyyy/MM/dd HH:mm")
    }

    val fileNameFormat: FastDateFormat by lazy {
        FastDateFormat.getInstance("yy-MM-dd-HH-mm-ss")
    }

    const val imagePathKey = "imagePath"

    val menuViewNames = arrayOf(
        "com.android.internal.view.menu.ListMenuItemView",
        "androidx.appcompat.view.menu.ListMenuItemView"
    )

    /** 上游取自 Material 的 design_appbar_elevation；桌面端用固定等价高度。 */
    val sysElevation: Int = 8

    private const val PREF_ANDROID_ID = "desktopDeviceId"

    /** 桌面端的"设备标识"：首次运行生成 UUID 并持久化，语义上等价于 ANDROID_ID。 */
    val androidId: String by lazy {
        val existing = appCtx.getPrefString(PREF_ANDROID_ID)
        if (!existing.isNullOrEmpty()) {
            existing
        } else {
            val generated = UUID.randomUUID().toString().replace("-", "")
            appCtx.putPrefString(PREF_ANDROID_ID, generated)
            generated
        }
    }

    val appInfo: AppInfo by lazy {
        AppInfo(
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            versionName = BuildConfig.VERSION_NAME,
            appVariant = if (BuildConfig.DEBUG) AppVariant.BETA_RELEASE else AppVariant.UNKNOWN
        )
    }

    val charsets =
        arrayListOf("UTF-8", "GB2312", "GB18030", "GBK", "Unicode", "UTF-16", "UTF-16LE", "ASCII")

    @Keep
    data class AppInfo(
        var versionCode: Long = 0L,
        var versionName: String = "",
        var appVariant: AppVariant = AppVariant.UNKNOWN
    )

    const val authority = BuildConfig.APPLICATION_ID + ".fileProvider"
}
