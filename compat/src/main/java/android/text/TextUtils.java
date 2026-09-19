package android.text;

/** JVM 兼容实现：legado 引擎仅使用 isEmpty / equals。 */
public final class TextUtils {
    private TextUtils() {}

    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        int length = a.length();
        if (length != b.length()) return false;
        if (a instanceof String && b instanceof String) return a.equals(b);
        for (int i = 0; i < length; i++) {
            if (a.charAt(i) != b.charAt(i)) return false;
        }
        return true;
    }

    public static String join(CharSequence delimiter, Iterable<?> tokens) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object t : tokens) {
            if (first) first = false; else sb.append(delimiter);
            sb.append(t);
        }
        return sb.toString();
    }

    public static String join(CharSequence delimiter, Object[] tokens) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object t : tokens) {
            if (first) first = false; else sb.append(delimiter);
            sb.append(t);
        }
        return sb.toString();
    }
}
