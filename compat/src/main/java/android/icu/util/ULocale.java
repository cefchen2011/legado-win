package android.icu.util;

import java.util.Locale;

/** JVM 兼容实现：直接映射到 java.util.Locale。 */
public final class ULocale {
    private final Locale locale;

    public static final ULocale SIMPLIFIED_CHINESE = new ULocale(Locale.SIMPLIFIED_CHINESE);
    public static final ULocale TRADITIONAL_CHINESE = new ULocale(Locale.TRADITIONAL_CHINESE);
    public static final ULocale CHINA = new ULocale(Locale.CHINA);
    public static final ULocale US = new ULocale(Locale.US);
    public static final ULocale ENGLISH = new ULocale(Locale.ENGLISH);
    public static final ULocale ROOT = new ULocale(Locale.ROOT);

    private ULocale(Locale locale) {
        this.locale = locale;
    }

    public static ULocale forLocale(Locale locale) {
        return new ULocale(locale);
    }

    public static ULocale forLanguageTag(String tag) {
        return new ULocale(Locale.forLanguageTag(tag));
    }

    public Locale toLocale() {
        return locale;
    }

    @Override
    public String toString() {
        return locale.toString();
    }
}
