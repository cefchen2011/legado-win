// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 ConvertExtensions.kt 的 ConvertUtils 混合了两类能力：
//   * 纯数据转换（toInt / toFloat / formatFileSize / toString）
//   * Android 图形转换（toBitmap / toDrawable，依赖 android.graphics）
// 桌面端保留前者（逐字实现），移除后者（图片由 Web UI 直接渲染）。
package io.legado.app.utils

import java.io.InputStream
import java.util.Locale

object ConvertUtils {

    fun toInt(obj: Any): Int {
        return try {
            obj.toString().trim().toInt()
        } catch (e: Exception) {
            0
        }
    }

    fun toInt(bytes: ByteArray): Int {
        return byteArrayToInt(bytes)
    }

    fun toFloat(obj: Any): Float {
        return try {
            obj.toString().trim().toFloat()
        } catch (e: Exception) {
            0F
        }
    }

    /** 由上游保持一致：多值拼接，用 tag 分隔 */
    fun toString(objects: Array<Any>, tag: String): String {
        var objectStr = ""
        for (obj in objects) {
            objectStr += obj.toString() + tag
        }
        return objectStr
    }

    fun toString(`is`: InputStream, charset: String = "utf-8"): String {
        return `is`.use { it.readBytes().toString(charset(charset)) }
    }

    /** 文件大小的人类可读格式，与上游实现一致 */
    fun formatFileSize(length: Long): String {
        if (length < 1024) return "$length B"
        val kb = length / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.2f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }

    private fun byteArrayToInt(bytes: ByteArray): Int {
        if (bytes.size != 4) return 0
        return (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)
    }
}

private fun charset(name: String): java.nio.charset.Charset =
    runCatching { java.nio.charset.Charset.forName(name) }
        .getOrDefault(Charsets.UTF_8)
