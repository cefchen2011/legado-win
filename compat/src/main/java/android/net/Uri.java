package android.net;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * JVM 兼容实现：覆盖 legado 引擎实际用到的解析、拼接与文件 URI 转换。
 * 不做 Android 的 ContentProvider 语义。
 */
public final class Uri {
    private final String raw;

    private Uri(String raw) {
        this.raw = raw;
    }

    public static Uri parse(String uriString) {
        if (uriString == null) throw new NullPointerException("uriString");
        return new Uri(uriString);
    }

    public static Uri fromFile(File file) {
        return new Uri(file.toURI().toString());
    }

    public static Uri fromParts(String scheme, String ssp, String fragment) {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) sb.append(scheme).append(':');
        if (ssp != null) sb.append(ssp);
        if (fragment != null) sb.append('#').append(fragment);
        return new Uri(sb.toString());
    }

    public String getScheme() {
        int i = raw.indexOf(':');
        return i < 0 ? null : raw.substring(0, i);
    }

    public String getPath() {
        try {
            return new URI(raw).getPath();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public String getLastPathSegment() {
        String p = getPath();
        if (p == null) return null;
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    public String getQuery() {
        int i = raw.indexOf('?');
        if (i < 0) return null;
        int j = raw.indexOf('#', i);
        return j < 0 ? raw.substring(i + 1) : raw.substring(i + 1, j);
    }

    public boolean isAbsolute() {
        String s = getScheme();
        return s != null && !s.isEmpty();
    }

    @Override
    public String toString() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Uri && raw.equals(((Uri) o).raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }
}
