// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游该枚举定义在 io/legado/app/help/update/AppReleaseInfo.kt 内，
// 而 help/update 整包依赖 Android 的下载安装流程，不参与移植。
// 这里单独抽出枚举，值与方法与上游完全一致。
package io.legado.app.help.update

enum class AppVariant {
    OFFICIAL,
    BETA_RELEASEA,
    BETA_RELEASES,
    BETA_RELEASE,
    UNKNOWN;

    fun isBeta(): Boolean {
        return this == BETA_RELEASE || this == BETA_RELEASEA
    }
}
