// [desktop-port] 桌面端替代实现，非上游源码。
// Android 由 AGP 生成 io.legado.app.BuildConfig；桌面端手写等价物。
package io.legado.app

object BuildConfig {
    const val DEBUG: Boolean = true
    const val APPLICATION_ID: String = "io.legado.win"
    const val BUILD_TYPE: String = "desktop"
    const val VERSION_NAME: String = "0.1.0"
    const val VERSION_CODE: Int = 1

    /** 上游由 AGP 注入的 Cronet 版本号；桌面端不启用 Cronet，仅保留默认 UA 所需的版本串。 */
    const val Cronet_Main_Version: String = "124.0.0.0"
}
