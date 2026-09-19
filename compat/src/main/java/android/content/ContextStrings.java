package android.content;

import java.util.HashMap;
import java.util.Map;

/**
 * 桌面端的字符串资源表。
 * Android 里 R.string.* 是编译期生成的资源 id，由 Resources 查表；
 * 桌面端没有 aapt，因此改为运行期注册：桌面版本的 R 类在初始化时注册文案。
 */
public final class ContextStrings {
    private ContextStrings() {}

    private static final Map<Integer, String> TABLE = new HashMap<>();

    public static void register(int id, String value) {
        synchronized (TABLE) {
            TABLE.put(id, value);
        }
    }

    public static String get(int id) {
        synchronized (TABLE) {
            String v = TABLE.get(id);
            return v != null ? v : ("res/" + Integer.toHexString(id));
        }
    }
}
