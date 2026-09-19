// 应用外壳：主题定义与存储。
//
// 上游 legado 的主题是"每个主题名对应一套配色"，日间/夜间各存一份
// （ThemeConfig.saveDayTheme / saveNightTheme）。桌面端沿用这个模型：
// 一套 ThemeDef 就是一个可命名、可切换、可删除的主题。
package legadowin

import com.google.gson.Gson
import io.legado.app.data.JsonTable
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import io.legado.app.help.config.ThemeConfig

/**
 * 一套主题配色。
 *
 * 颜色都是 ARGB 整数，与引擎的 `ThemeConfig.Config` 保持一致——
 * 应用主题时直接写回 ThemeConfig，前端再从那里读，避免两套颜色来源。
 */
data class ThemeDef(
    var name: String = "",
    /** 这套配色用于日间还是夜间（决定它覆盖 ThemeConfig 的哪一组字段） */
    var isNight: Boolean = false,
    var background: Int = ThemeConfig.DEFAULT_BACKGROUND,
    var textColor: Int = ThemeConfig.DEFAULT_TEXT,
    var mutedColor: Int = ThemeConfig.DEFAULT_MUTED,
    var panelColor: Int = ThemeConfig.DEFAULT_PANEL,
    var borderColor: Int = ThemeConfig.DEFAULT_BORDER,
    var primary: Int = ThemeConfig.DEFAULT_PRIMARY,
    var accent: Int = ThemeConfig.DEFAULT_ACCENT,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name,
        "isNight" to isNight,
        "background" to background,
        "textColor" to textColor,
        "mutedColor" to mutedColor,
        "panelColor" to panelColor,
        "borderColor" to borderColor,
        "primary" to primary,
        "accent" to accent,
    )

    companion object {
        /**
         * 内置预设。legado 自带若干主题，这里给几套常用的，
         * 用户可以在其基础上改，也可以直接新建。
         */
        fun presets(): List<ThemeDef> = listOf(
            ThemeDef(
                name = "默认（明亮）",
                background = 0xFFFFFFFF.toInt(),
                textColor = 0xFF1A1A1A.toInt(),
                mutedColor = 0xFF8A8A8E.toInt(),
                panelColor = 0xFFF5F5F7.toInt(),
                borderColor = 0xFFE4E4E8.toInt(),
                primary = 0xFF3F51B5.toInt(),
                accent = 0xFFFF4081.toInt(),
            ),
            ThemeDef(
                name = "护眼（米黄）",
                background = 0xFFF5EFDC.toInt(),
                textColor = 0xFF3A3226.toInt(),
                mutedColor = 0xFF8C8574.toInt(),
                panelColor = 0xFFEFE8D2.toInt(),
                borderColor = 0xFFDDD5BC.toInt(),
                primary = 0xFF7A6A45.toInt(),
                accent = 0xFFB8860B.toInt(),
            ),
            ThemeDef(
                name = "青灰",
                background = 0xFFE8EEEC.toInt(),
                textColor = 0xFF22302C.toInt(),
                mutedColor = 0xFF6E7F7A.toInt(),
                panelColor = 0xFFDFE7E4.toInt(),
                borderColor = 0xFFC9D5D1.toInt(),
                primary = 0xFF2E6E63.toInt(),
                accent = 0xFF00897B.toInt(),
            ),
            ThemeDef(
                name = "夜间（默认）",
                isNight = true,
                background = 0xFF121212.toInt(),
                textColor = 0xFFC8C8C8.toInt(),
                mutedColor = 0xFF7A7A7E.toInt(),
                panelColor = 0xFF1E1E1E.toInt(),
                borderColor = 0xFF2C2C2E.toInt(),
                primary = 0xFF7986CB.toInt(),
                accent = 0xFFFF80AB.toInt(),
            ),
            ThemeDef(
                name = "夜间（纯黑 OLED）",
                isNight = true,
                background = 0xFF000000.toInt(),
                textColor = 0xFFB0B0B0.toInt(),
                mutedColor = 0xFF6A6A6E.toInt(),
                panelColor = 0xFF0A0A0A.toInt(),
                borderColor = 0xFF1F1F1F.toInt(),
                primary = 0xFF5C6BC0.toInt(),
                accent = 0xFFFF4081.toInt(),
            ),
            ThemeDef(
                name = "夜间（暖褐）",
                isNight = true,
                background = 0xFF1C1814.toInt(),
                textColor = 0xFFC4B8A8.toInt(),
                mutedColor = 0xFF8A7F70.toInt(),
                panelColor = 0xFF262019.toInt(),
                borderColor = 0xFF352C22.toInt(),
                primary = 0xFFA1887F.toInt(),
                accent = 0xFFD4A373.toInt(),
            ),
        )
    }
}

/** 自定义主题的持久化（沿用引擎的 JSON 表，与其它数据一致） */
object ThemeStore {
    private val table = JsonTable("themes", ThemeDef::class.java) { it.name }
    private val gson = Gson()

    fun all(): List<ThemeDef> = table.all()

    fun get(name: String): ThemeDef? = table.get(name)

    fun save(theme: ThemeDef) = table.put(theme)

    fun delete(name: String) = table.remove(name)

    /** 当前启用中的主题名（日间/夜间各一个） */
    fun activeName(isNight: Boolean): String =
        splitties.init.appCtx.getPrefString(if (isNight) "activeNightTheme" else "activeDayTheme")
            ?: ""

    fun setActiveName(isNight: Boolean, name: String) {
        splitties.init.appCtx.putPrefString(
            if (isNight) "activeNightTheme" else "activeDayTheme", name
        )
    }

    /**
     * 把一套主题写进引擎的 ThemeConfig。
     *
     * 日间主题只覆盖日间那组字段，夜间主题只覆盖夜间那组——
     * 这样切到夜间时仍是用户调好的夜间配色，而不会被日间配色冲掉。
     */
    fun apply(theme: ThemeDef) {
        val c = ThemeConfig.getDurConfig()
        if (theme.isNight) {
            c.backgroundNight = theme.background
            c.textColorNight = theme.textColor
            c.mutedColorNight = theme.mutedColor
            c.panelColorNight = theme.panelColor
            c.borderColorNight = theme.borderColor
            c.nameNight = theme.name
        } else {
            c.background = theme.background
            c.textColor = theme.textColor
            c.mutedColor = theme.mutedColor
            c.panelColor = theme.panelColor
            c.borderColor = theme.borderColor
            c.name = theme.name
        }
        // 主色/强调色是全局的，两套都写
        c.primary = theme.primary
        c.accent = theme.accent
        ThemeConfig.upConfig(c)
        setActiveName(theme.isNight, theme.name)
    }

    /** 从当前 ThemeConfig 反推出一套主题（用于"另存为"） */
    fun snapshotCurrent(name: String, isNight: Boolean): ThemeDef {
        val c = ThemeConfig.getDurConfig()
        return if (isNight) {
            ThemeDef(
                name = name, isNight = true,
                background = c.backgroundNight, textColor = c.textColorNight,
                mutedColor = c.mutedColorNight, panelColor = c.panelColorNight,
                borderColor = c.borderColorNight, primary = c.primary, accent = c.accent,
            )
        } else {
            ThemeDef(
                name = name, isNight = false,
                background = c.background, textColor = c.textColor,
                mutedColor = c.mutedColor, panelColor = c.panelColor,
                borderColor = c.borderColor, primary = c.primary, accent = c.accent,
            )
        }
    }

    /** 当前生效的完整配色（含预设兜底） */
    fun presetsOrEmpty(): Map<String, ThemeDef> = ThemeDef.presets().associateBy { it.name }
}
