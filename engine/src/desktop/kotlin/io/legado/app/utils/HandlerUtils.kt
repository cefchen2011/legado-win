// [desktop-port] 桌面端替代实现，非上游源码。
// 上游 HandlerUtils 依赖 android.os.Handler / Looper；桌面端没有 UI 线程模型，
// 这里把"主线程"定义为初始化该类时所在的线程（通常是 JVM main 线程）。
package io.legado.app.utils

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/** 桌面端的"主线程"：进程启动线程。 */
object DesktopThreads {
    @Volatile
    var mainThread: Thread = Thread.currentThread()

    /** 由应用外壳在启动时显式指定 UI 线程（可选）。 */
    fun markAsMain() {
        mainThread = Thread.currentThread()
    }

    val singleExecutor by lazy {
        Executors.newSingleThreadExecutor(ThreadFactory { r ->
            Thread(r, "legado-main").apply { isDaemon = true }
        })
    }
}

/** 与上游同名同义：当前是否运行在"主线程"上。 */
val isMainThread: Boolean
    get() = DesktopThreads.mainThread === Thread.currentThread()

/**
 * 上游返回 android.os.Handler；桌面端没有 Handler 类型，
 * 因此保留一个最小投递器：post 到主线程串行执行器。
 */
fun runOnMain(block: () -> Unit) {
    if (isMainThread) block() else DesktopThreads.singleExecutor.execute(block)
}

/**
 * 上游签名 buildMainHandler()。桌面端返回一个轻量句柄，
 * 支持 post / postDelayed / removeCallbacks，语义与 Android Handler 一致。
 */
fun buildMainHandler(): DesktopHandler = DesktopHandler

object DesktopHandler {

    private val pending = java.util.concurrent.ConcurrentHashMap<Any, java.util.concurrent.ScheduledFuture<*>>()

    private val scheduler by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "legado-handler").apply { isDaemon = true }
        }
    }

    fun post(token: Any? = null, block: () -> Unit) {
        runOnMain(block)
    }

    fun postDelayed(token: Any?, delayMillis: Long, block: () -> Unit) {
        val future = scheduler.schedule({ runOnMain(block) }, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (token != null) pending[token] = future
    }

    fun removeCallbacks(token: Any) {
        pending.remove(token)?.cancel(false)
    }
}
