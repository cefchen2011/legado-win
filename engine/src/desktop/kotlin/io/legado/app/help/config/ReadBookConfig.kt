// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 ReadBookConfig 有 30KB / 500 余行，是阅读界面的排版与主题配置，
// 大量依赖 android.graphics（Bitmap/Color/Canvas）与 DefaultData、BitmapUtils 等
// Android 资源，不适合原样移植。
//
// 这里提供桌面版：保留上游同名的配置项（类型一致），底层改用 SharedPreferences。
// 排版相关的派生量（行高像素、分页尺寸）由桌面 UI 层按 CSS 计算，不再需要 Bitmap。
// 键名优先复用 PreferKey 中已有的常量；上游把部分键写字面量的，这里沿用同一字面量。
package io.legado.app.help.config

import android.content.Context
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

object ReadBookConfig {

    // 上游未收进 PreferKey 的键，沿用其字面量名，便于与 Android 端配置互通
    private const val KEY_PARAGRAPH_INDENT = "paragraphIndent"
    private const val KEY_PAGE_ANIM = "pageAnim"
    private const val KEY_PAGE_ANIM_EINK = "pageAnimEInk"
    private const val KEY_FONT_SIZE = "fontSize"
    private const val KEY_LINE_SPACING_EXTRA = "lineSpacingExtra"
    private const val KEY_TITLE_MODE = "titleMode"
    private const val KEY_PADDING_TOP = "paddingTop"
    private const val KEY_PADDING_BOTTOM = "paddingBottom"

    const val DEFAULT_PARAGRAPH_INDENT = "\u3000\u3000"

    // ------------------------------------------------------------ 排版
    /** 段落缩进，上游默认两个全角空格 */
    var paragraphIndent: String
        get() = appCtx.getPrefString(KEY_PARAGRAPH_INDENT) ?: DEFAULT_PARAGRAPH_INDENT
        set(value) = appCtx.putPrefString(KEY_PARAGRAPH_INDENT, value)

    /** 正文行高是否按字号倍数计算 */
    var readBodyToLh: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readBodyToLh, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.readBodyToLh, value)

    /** 是否使用中文排版（标点挤压、行首行尾禁则） */
    var useZhLayout: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.useZhLayout, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.useZhLayout, value)

    /** 翻页动画，取值见 [PageAnim] */
    var pageAnim: Int
        get() = appCtx.getPrefInt(KEY_PAGE_ANIM, PageAnim.coverPageAnim)
        set(value) = appCtx.putPrefInt(KEY_PAGE_ANIM, value)

    /** 墨水屏翻页动画 */
    var pageAnimEInk: Int
        get() = appCtx.getPrefInt(KEY_PAGE_ANIM_EINK, PageAnim.noAnim)
        set(value) = appCtx.putPrefInt(KEY_PAGE_ANIM_EINK, value)

    // ------------------------------------------------------------ 字号与行距
    var fontSize: Int
        get() = appCtx.getPrefInt(KEY_FONT_SIZE, 20)
        set(value) = appCtx.putPrefInt(KEY_FONT_SIZE, value)

    var lineSpacingExtra: Int
        get() = appCtx.getPrefInt(KEY_LINE_SPACING_EXTRA, 8)
        set(value) = appCtx.putPrefInt(KEY_LINE_SPACING_EXTRA, value)

    var textFullJustify: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.textFullJustify, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.textFullJustify, value)

    var textBottomJustify: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.textBottomJustify, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.textBottomJustify, value)

    var titleMode: Int
        get() = appCtx.getPrefInt(KEY_TITLE_MODE, 0)
        set(value) = appCtx.putPrefInt(KEY_TITLE_MODE, value)

    var paddingTop: Int
        get() = appCtx.getPrefInt(KEY_PADDING_TOP, 0)
        set(value) = appCtx.putPrefInt(KEY_PADDING_TOP, value)

    var paddingBottom: Int
        get() = appCtx.getPrefInt(KEY_PADDING_BOTTOM, 0)
        set(value) = appCtx.putPrefInt(KEY_PADDING_BOTTOM, value)

    /** 上游为 Bitmap 背景图；桌面端交给前端 CSS，这里只保留路径配置。 */
    var bgImagePath: String?
        get() = appCtx.getPrefString(PreferKey.bgImage)
        set(value) = appCtx.putPrefString(PreferKey.bgImage, value)

    /** 供桌面 UI 层一次性读取排版配置 */
    fun snapshot(context: Context = appCtx): Map<String, Any?> = durConfig.toMap()

    /**
     * 上游以 `ReadBookConfig.durConfig` 暴露排版配置快照（含 toMap()），
     * JsExtensions 的 getReadBookConfig / getReadBookConfigMap 依赖该形状。
     */
    val durConfig: Config
        get() = Config(
            paragraphIndent = paragraphIndent,
            readBodyToLh = readBodyToLh,
            useZhLayout = useZhLayout,
            pageAnim = pageAnim,
            pageAnimEInk = pageAnimEInk,
            fontSize = fontSize,
            lineSpacingExtra = lineSpacingExtra,
            textFullJustify = textFullJustify,
            textBottomJustify = textBottomJustify,
            titleMode = titleMode,
            paddingTop = paddingTop,
            paddingBottom = paddingBottom,
            bgImagePath = bgImagePath,
        )

    data class Config(
        var paragraphIndent: String = DEFAULT_PARAGRAPH_INDENT,
        var readBodyToLh: Boolean = true,
        var useZhLayout: Boolean = false,
        var pageAnim: Int = PageAnim.coverPageAnim,
        var pageAnimEInk: Int = PageAnim.noAnim,
        var fontSize: Int = 20,
        var lineSpacingExtra: Int = 8,
        var textFullJustify: Boolean = false,
        var textBottomJustify: Boolean = false,
        var titleMode: Int = 0,
        var paddingTop: Int = 0,
        var paddingBottom: Int = 0,
        var bgImagePath: String? = null,
    ) {
        /** 上游 toMap() 返回 Map<String, Any>（不含空值），JsExtensions 依赖该类型 */
        fun toMap(): Map<String, Any> {
            val map = LinkedHashMap<String, Any>()
            map["paragraphIndent"] = paragraphIndent
            map["readBodyToLh"] = readBodyToLh
            map["useZhLayout"] = useZhLayout
            map["pageAnim"] = pageAnim
            map["pageAnimEInk"] = pageAnimEInk
            map["fontSize"] = fontSize
            map["lineSpacingExtra"] = lineSpacingExtra
            map["textFullJustify"] = textFullJustify
            map["textBottomJustify"] = textBottomJustify
            map["titleMode"] = titleMode
            map["paddingTop"] = paddingTop
            map["paddingBottom"] = paddingBottom
            bgImagePath?.let { map["bgImagePath"] = it }
            return map
        }
    }
}
