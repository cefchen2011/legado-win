// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 CrashHandler 把崩溃堆栈写入应用目录并弹提示；桌面端写成日志文件。
package io.legado.app.help

import io.legado.app.data.DesktopPaths
import io.legado.app.utils.LogUtils
import io.legado.app.utils.stackTraceToStringSafe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

    private const val TAG = "CrashHandler"

    /** 桌面端把未捕获异常追加到崩溃日志 */
    fun handle(e: Throwable) {
        LogUtils.e(TAG, "未捕获异常: ${e.message}", e)
        runCatching {
            val dir = DesktopPaths.logDir
            val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault()).format(Date())
            File(dir, "crash-$name.log").writeText(
                e.stackTraceToStringSafe(),
                Charsets.UTF_8
            )
        }
    }

    /** 安装全局未捕获异常处理（由应用外壳在启动时调用） */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            handle(e)
            previous?.uncaughtException(thread, e)
        }
    }

    /** 上游签名：把崩溃信息写入文件并返回文件对象 */
    fun saveCrashInfo2File(e: Throwable): File? {
        handle(e)
        return runCatching {
            val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault()).format(Date())
            val f = File(DesktopPaths.logDir, "crash-$name.log")
            f.writeText(e.stackTraceToStringSafe(), Charsets.UTF_8)
            f
        }.getOrNull()
    }
}
