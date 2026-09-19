package android.icu.text;

import android.icu.util.ULocale;

/** JVM 兼容实现：委托 java.text.Collator（桌面端按 Unicode 排序规则比较）。 */
public abstract class Collator {
    private Collator() {}

    public static final int PRIMARY = 0;
    public static final int SECONDARY = 1;
    public static final int TERTIARY = 2;
    public static final int DEFAULT = 2;

    private static final class Impl extends Collator {
        private final java.text.Collator delegate;

        Impl(java.text.Collator delegate) {
            this.delegate = delegate;
        }

        @Override
        public int compare(String source, String target) {
            return delegate.compare(source, target);
        }
    }

    public abstract int compare(String source, String target);

    public static Collator getInstance() {
        return new Impl(java.text.Collator.getInstance());
    }

    public static Collator getInstance(ULocale locale) {
        return new Impl(java.text.Collator.getInstance(locale.toLocale()));
    }

    public static Collator getInstance(java.util.Locale locale) {
        return new Impl(java.text.Collator.getInstance(locale));
    }
}
