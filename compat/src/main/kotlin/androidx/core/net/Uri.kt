package androidx.core.net

import android.net.Uri

/** androidx.core 的 String.toUri() 扩展，桌面端直接委托给兼容层的 Uri。 */
fun String.toUri(): Uri = Uri.parse(this)
