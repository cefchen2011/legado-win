package android.webkit;

import android.content.Context;

/** 仅提供引擎引用到的常量与静态方法。 */
public abstract class WebSettings {
    public enum LayoutAlgorithm { NORMAL, SINGLE_COLUMN, NARROW_COLUMNS, TEXT_AUTOSIZING }
    public static final int MIXED_CONTENT_ALWAYS_ALLOW = 0;
    public static final int MIXED_CONTENT_NEVER_ALLOW = 1;
    public static final int MIXED_CONTENT_COMPATIBILITY_MODE = 2;
    public static final int LOAD_DEFAULT = -1;
    public static final int LOAD_CACHE_ELSE_NETWORK = 1;
    public static final int LOAD_NO_CACHE = 2;
    public static final int LOAD_CACHE_ONLY = 3;

    /**
     * 上游用它取 WebView 的默认 UA；桌面端没有 WebView，
     * 返回等价的桌面 Chrome UA 字符串。
     */
    public static String getDefaultUserAgent(Context context) {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    }
}
