// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 ThemeConfig（20KB）基于 android.graphics 的颜色/Bitmap 与主题资源。
// 桌面端配色交给前端 CSS，这里保留上游同名 API：
//   getDurConfig(context) 返回当前主题的 Config，Config.toMap() 供 JS 侧读取
//   （JsExtensions 的 getThemeConfig / getThemeConfigMap 依赖此形状）
package io.legado.app.help.config

import android.content.Context
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.getPrefString
import splitties.init.appCtx

object ThemeConfig {

    /**
     * 对应上游的主题配置快照。
     *
     * 上游的 Config 字段更多（含背景图、沉浸式导航栏等 Android 专属项）。
     * 桌面端保留其形状并补齐**配色相关**的部分——主题编辑器需要能分别调
     * 背景 / 正文 / 次要文字 / 面板 / 分隔线 / 主色 / 强调色，
     * 日间与夜间各一套。
     */
    data class Config(
        var primary: Int = DEFAULT_PRIMARY,
        var accent: Int = DEFAULT_ACCENT,
        var background: Int = DEFAULT_BACKGROUND,
        var backgroundNight: Int = DEFAULT_BACKGROUND_NIGHT,
        var isNightTheme: Boolean = false,
        var name: String = "",
        var nameNight: String = "",
        // ---- 桌面端补齐的配色项 ----
        var textColor: Int = DEFAULT_TEXT,
        var textColorNight: Int = DEFAULT_TEXT_NIGHT,
        var mutedColor: Int = DEFAULT_MUTED,
        var mutedColorNight: Int = DEFAULT_MUTED_NIGHT,
        var panelColor: Int = DEFAULT_PANEL,
        var panelColorNight: Int = DEFAULT_PANEL_NIGHT,
        var borderColor: Int = DEFAULT_BORDER,
        var borderColorNight: Int = DEFAULT_BORDER_NIGHT,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "primary" to primary,
            "accent" to accent,
            "background" to background,
            "backgroundNight" to backgroundNight,
            "isNightTheme" to isNightTheme,
            "name" to name,
            "nameNight" to nameNight,
            "textColor" to textColor,
            "textColorNight" to textColorNight,
            "mutedColor" to mutedColor,
            "mutedColorNight" to mutedColorNight,
            "panelColor" to panelColor,
            "panelColorNight" to panelColorNight,
            "borderColor" to borderColor,
            "borderColorNight" to borderColorNight,
        )

        /** 按当前昼夜取某一项的实际颜色 */
        fun pick(key: String): Int = when (key) {
            "primary" -> primary
            "accent" -> accent
            "background" -> if (isNightTheme) backgroundNight else background
            "textColor" -> if (isNightTheme) textColorNight else textColor
            "mutedColor" -> if (isNightTheme) mutedColorNight else mutedColor
            "panelColor" -> if (isNightTheme) panelColorNight else panelColor
            "borderColor" -> if (isNightTheme) borderColorNight else borderColor
            else -> 0
        }
    }

    // 桌面端默认配色（ARGB）
    const val DEFAULT_PRIMARY = 0xFF3F51B5.toInt()
    const val DEFAULT_ACCENT = 0xFFFF4081.toInt()
    const val DEFAULT_BACKGROUND = 0xFFFFFFFF.toInt()
    const val DEFAULT_BACKGROUND_NIGHT = 0xFF121212.toInt()
    const val DEFAULT_TEXT = 0xFF1A1A1A.toInt()
    const val DEFAULT_TEXT_NIGHT = 0xFFC8C8C8.toInt()
    const val DEFAULT_MUTED = 0xFF8A8A8E.toInt()
    const val DEFAULT_MUTED_NIGHT = 0xFF7A7A7E.toInt()
    const val DEFAULT_PANEL = 0xFFF5F5F7.toInt()
    const val DEFAULT_PANEL_NIGHT = 0xFF1E1E1E.toInt()
    const val DEFAULT_BORDER = 0xFFE4E4E8.toInt()
    const val DEFAULT_BORDER_NIGHT = 0xFF2C2C2E.toInt()

    /** 当前是否夜间主题 */
    var isNightTheme: Boolean = false

    fun getDurConfig(context: Context = appCtx): Config {
        return Config(
            isNightTheme = isNightTheme,
            name = context.getPrefString("dThemeName") ?: "",
            nameNight = context.getPrefString("dNThemeName") ?: "",
            primary = context.getPrefInt("cPrimary", DEFAULT_PRIMARY),
            accent = context.getPrefInt("cAccent", DEFAULT_ACCENT),
            background = context.getPrefInt("cBackground", DEFAULT_BACKGROUND),
            backgroundNight = context.getPrefInt("cNBackground", DEFAULT_BACKGROUND_NIGHT),
            textColor = context.getPrefInt("cText", DEFAULT_TEXT),
            textColorNight = context.getPrefInt("cNText", DEFAULT_TEXT_NIGHT),
            mutedColor = context.getPrefInt("cMuted", DEFAULT_MUTED),
            mutedColorNight = context.getPrefInt("cNMuted", DEFAULT_MUTED_NIGHT),
            panelColor = context.getPrefInt("cPanel", DEFAULT_PANEL),
            panelColorNight = context.getPrefInt("cNPanel", DEFAULT_PANEL_NIGHT),
            borderColor = context.getPrefInt("cBorder", DEFAULT_BORDER),
            borderColorNight = context.getPrefInt("cNBorder", DEFAULT_BORDER_NIGHT),
        )
    }

    /**
     * 保存主题配置。
     * 上游由主题编辑器调用；桌面端的主题编辑器直接改这些颜色，
     * 因此这里要把每一项真正写进配置，而不只是刷新内存标志。
     */
    fun upConfig(config: Config) {
        isNightTheme = config.isNightTheme
        appCtx.putPrefInt("cPrimary", config.primary)
        appCtx.putPrefInt("cAccent", config.accent)
        appCtx.putPrefInt("cBackground", config.background)
        appCtx.putPrefInt("cNBackground", config.backgroundNight)
        appCtx.putPrefInt("cText", config.textColor)
        appCtx.putPrefInt("cNText", config.textColorNight)
        appCtx.putPrefInt("cMuted", config.mutedColor)
        appCtx.putPrefInt("cNMuted", config.mutedColorNight)
        appCtx.putPrefInt("cPanel", config.panelColor)
        appCtx.putPrefInt("cNPanel", config.panelColorNight)
        appCtx.putPrefInt("cBorder", config.borderColor)
        appCtx.putPrefInt("cNBorder", config.borderColorNight)
        appCtx.putPrefString("dThemeName", config.name)
        appCtx.putPrefString("dNThemeName", config.nameNight)
    }

    /** 恢复出厂配色 */
    fun reset() {
        upConfig(
            Config(
                primary = DEFAULT_PRIMARY,
                accent = DEFAULT_ACCENT,
                background = DEFAULT_BACKGROUND,
                backgroundNight = DEFAULT_BACKGROUND_NIGHT,
            )
        )
    }
}
