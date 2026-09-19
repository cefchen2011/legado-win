package androidx.core.net;

import java.net.URI;
import java.net.URISyntaxException;

/** androidx.core.net 的 Uri 最小实现，供 Kotlin 扩展 toUri 使用。 */
public final class UriCompat {
    private UriCompat() {}

    public static URI parse(String s) {
        try {
            return new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad uri: " + s, e);
        }
    }
}
