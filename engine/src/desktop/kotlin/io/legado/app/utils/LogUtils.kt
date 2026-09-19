// [desktop-port] 桌面端替代实现，非上游源码。
// 上游 io/legado/app/utils/LogUtils.kt 依赖 android.util.Log 与 Android 日志文件目录，
// 这里保留同名同签名的公开 API，改为输出到标准流与桌面日志文件。
package io.legado.app.utils

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val TIME_PATTERN = "HH:mm:ss.SSS"

@Suppress("unused", "MemberVisibilityCanBePrivate")
object LogUtils {

    val logTimeFormat: SimpleDateFormat by lazy { SimpleDateFormat(TIME_PATTERN, Locale.getDefault()) }

    /** 是否输出调试日志；桌面端默认开启，可由应用外壳关闭。 */
    var isDebug: Boolean = true

    /** 日志文件（可选）。设置后日志会同时落盘。 */
    var logFile: File? = null

    private val lock = Any()

    /** 上游签名为 init(context)，桌面端没有 Context，保留签名以便原样调用。 */
    fun init(context: Any?) {
        // 桌面端不需要初始化
    }

    private fun write(level: String, tag: String, msg: String) {
        val line = "${logTimeFormat.format(Date())} $level/$tag: $msg"
        if (level == "E") System.err.println(line) else println(line)
        logFile?.let { f ->
            synchronized(lock) {
                runCatching {
                    f.parentFile?.mkdirs()
                    f.appendText(line + System.lineSeparator())
                }
            }
        }
    }

    fun d(tag: String, msg: String) {
        if (isDebug) write("D", tag, msg)
    }

    fun i(tag: String, msg: String) = write("I", tag, msg)

    fun w(tag: String, msg: String) = write("W", tag, msg)

    fun e(tag: String, msg: String) = write("E", tag, msg)

    fun e(tag: String, msg: String, tr: Throwable?) {
        write("E", tag, msg)
        tr?.printStackTrace()
    }

    /** 上游用于提升日志等级，桌面端保持调试输出。 */
    fun upLevel() {
        isDebug = true
    }

    fun getCurrentDateStr(pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

    fun logDeviceInfo() {
        d(
            "DeviceInfo",
            "os=${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                "arch=${System.getProperty("os.arch")} java=${System.getProperty("java.version")}"
        )
    }
}

/** 上游语义：仅调试期打印。 */
fun Throwable.printOnDebug() {
    if (LogUtils.isDebug) printStackTrace()
}

/** 日志/异常信息转字符串，上游 ThrowableExtensions 同名函数之外的调试辅助。 */
fun Throwable.stackTraceToStringSafe(): String {
    val sw = StringWriter()
    printStackTrace(PrintWriter(sw))
    return sw.toString()
}
