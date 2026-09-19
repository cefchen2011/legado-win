// [desktop-port] 桌面端替代实现，非上游源码。
//
// CanvasRecorder 是 Android 上把阅读界面绘制到硬件加速图层以提升翻页性能的设施。
// 桌面端由 Web UI 的合成器负责渲染，因此这里只保留 API 形状并声明不支持。
package io.legado.app.utils.canvasrecorder

object CanvasRecorderFactory {

    /** 桌面端不启用 Android 的 canvas 录制优化 */
    const val isSupport: Boolean = false

    fun createRecorder(): CanvasRecorder? = null
}

interface CanvasRecorder {
    fun start()
    fun stop()
    fun release()
}
