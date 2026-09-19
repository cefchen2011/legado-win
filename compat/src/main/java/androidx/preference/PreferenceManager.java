package androidx.preference;

import android.content.Context;
import android.content.DesktopSharedPreferences;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** JVM 兼容实现：default SharedPreferences 的等价物。 */
public final class PreferenceManager {

    private static final Map<String, SharedPreferences> CACHE = new ConcurrentHashMap<>();

    private PreferenceManager() {}

    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        return CACHE.computeIfAbsent("default", DesktopSharedPreferences::new);
    }

    public static void setDefaultValues(Context context, String name) {
        // 桌面端没有 XML 默认值资源，首次访问即为空配置
    }
}
