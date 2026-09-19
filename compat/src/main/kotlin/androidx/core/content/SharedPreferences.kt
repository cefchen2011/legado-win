package androidx.core.content

import android.content.SharedPreferences

/**
 * androidx.core-ktx 的 SharedPreferences.edit {} 扩展，桌面端等价实现。
 * 上游大量配置写入都走这个扩展。
 */
inline fun SharedPreferences.edit(
    commit: Boolean = false,
    action: SharedPreferences.Editor.() -> Unit,
) {
    val editor = edit()
    editor.action()
    if (commit) editor.commit() else editor.apply()
}
