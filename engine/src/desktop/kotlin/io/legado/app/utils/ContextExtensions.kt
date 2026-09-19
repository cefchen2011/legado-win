// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 ContextExtensions 有 420 行，绝大多数是 Android UI / 权限 / 主题相关。
// 这里只实现引擎与配置层实际需要的部分：
//   * 应用目录（externalCache / externalFiles）
//   * 进程重启
//   * SharedPreferences 读写扩展（忠实照搬上游同名同签名实现，
//     机制由 compat 层的 DesktopSharedPreferences 承载）
package io.legado.app.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.io.File

/** Android 的外部缓存目录 => 桌面端应用数据目录下的 external-cache */
val Context.externalCache: File
    get() = getExternalCacheDir() ?: File(getCacheDir().parentFile, "external-cache")

/** Android 的外部文件目录 => 桌面端应用数据目录下的 external-files */
val Context.externalFiles: File
    get() = getExternalFilesDir(null) ?: File(getFilesDir().parentFile, "external-files")

/** Android 的进程重启；桌面端标记为待重启，由应用外壳决定是否真正重启。 */
fun Context.restart() {
    LogUtils.w("ContextExtensions", "桌面端不支持进程重启，已忽略该请求")
}

/**
 * 上游取自 Android 的 Resources.Configuration（屏幕方向、夜间模式等）。
 * 桌面端用一份等价的可变配置承载，由应用外壳在系统主题变化时更新。
 */
object DesktopConfiguration {
    @Volatile
    var isNightMode: Boolean = false
        private set

    /** 对应 Android Configuration.ORIENTATION_* */
    @Volatile
    var orientation: Int = ORIENTATION_PORTRAIT

    /** 对应 Android Configuration.uiMode 的夜间位 */
    @Volatile
    var uiMode: Int = UI_MODE_NIGHT_NO

    /** 由应用外壳在系统主题变化时调用 */
    fun updateNightMode(night: Boolean) {
        isNightMode = night
        uiMode = if (night) UI_MODE_NIGHT_YES else UI_MODE_NIGHT_NO
    }

    const val ORIENTATION_PORTRAIT = 1
    const val ORIENTATION_LANDSCAPE = 2
    const val UI_MODE_NIGHT_NO = 0x10
    const val UI_MODE_NIGHT_YES = 0x20
}

/**
 * 上游签名：`val sysConfiguration`。
 * AppConfig 等上游代码以**无接收者**的方式使用它（`sysConfiguration.isNightMode`），
 * 因此这里保持顶层 val 形态。
 */
val sysConfiguration: DesktopConfiguration get() = DesktopConfiguration

/** 上游另有基于 Context 的取用方式，保留以便调用点零改动。 */
val Context.sysConfiguration: DesktopConfiguration get() = DesktopConfiguration

/** 上游签名：val Context.isNightMode */
val Context.isNightMode: Boolean
    get() = DesktopConfiguration.isNightMode

// ---------------------------------------------------------------- 配置读写
// 以下与上游 ContextExtensions.kt 第 163-199 行保持同名同签名，
// 使 AppConfig 等上游文件可以原样移植。

val Context.defaultSharedPreferences: SharedPreferences
    get() = PreferenceManager.getDefaultSharedPreferences(this)

fun Context.getPrefBoolean(key: String, defValue: Boolean = false) =
    defaultSharedPreferences.getBoolean(key, defValue)

fun Context.putPrefBoolean(key: String, value: Boolean = false) =
    defaultSharedPreferences.edit { putBoolean(key, value) }

fun Context.getPrefInt(key: String, defValue: Int = 0) =
    defaultSharedPreferences.getInt(key, defValue)

fun Context.putPrefInt(key: String, value: Int) =
    defaultSharedPreferences.edit { putInt(key, value) }

fun Context.getPrefLong(key: String, defValue: Long = 0L) =
    defaultSharedPreferences.getLong(key, defValue)

fun Context.putPrefLong(key: String, value: Long) =
    defaultSharedPreferences.edit { putLong(key, value) }

fun Context.getPrefString(key: String, defValue: String? = null) =
    defaultSharedPreferences.getString(key, defValue)

fun Context.putPrefString(key: String, value: String?) =
    defaultSharedPreferences.edit { putString(key, value) }

fun Context.getPrefStringSet(
    key: String,
    defValue: MutableSet<String>? = null,
): MutableSet<String>? = defaultSharedPreferences.getStringSet(key, defValue)

fun Context.putPrefStringSet(key: String, value: MutableSet<String>) =
    defaultSharedPreferences.edit { putStringSet(key, value) }

fun Context.removePref(key: String) =
    defaultSharedPreferences.edit { remove(key) }

// ---------------------------------------------------------------- 页面跳转
// 上游用 `context.startActivity<XxxActivity> { putExtra(...) }` 启动界面。
// 桌面端没有 Activity，改为把"打开某个界面 + 参数"交给应用外壳路由。

object DesktopActivityRouter {
    /** 由应用外壳注册；参数为目标界面类与实际参数表。 */
    var launcher: ((target: Class<*>, extras: Map<String, Any?>) -> Unit)? = null
}

/**
 * 与上游同名同签名：`startActivity<T> { putExtra(...) }`。
 *
 * 注意：Context 上已有成员 `startActivity(Intent)`，但本扩展带类型参数，
 * 因此显式写 `startActivity<Xxx> { }` 时解析到这个扩展，与上游一致。
 */
inline fun <reified T : Any> Context.startActivity(noinline intentAction: Intent.() -> Unit = {}) {
    val intent = Intent().apply(intentAction)
    val extras = intent.extrasSnapshot()
    LogUtils.i("startActivity", "打开界面 ${T::class.simpleName}，参数 $extras")
    DesktopActivityRouter.launcher?.invoke(T::class.java, extras)
}

/** 上游还有带 Context 参数的重载（在非 Context 接收者上调用）。 */
inline fun <reified T : Any> Any.startActivity(context: Context, noinline intentAction: Intent.() -> Unit = {}) {
    context.startActivity<T>(intentAction)
}
