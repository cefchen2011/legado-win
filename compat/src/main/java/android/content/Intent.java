package android.content;

import java.util.HashMap;
import java.util.Map;

/** 最小 Intent：仅承载桌面端需要透传的键值。 */
public class Intent {
    private final Map<String, Object> extras = new HashMap<>();
    private android.net.Uri data;

    /** 对应 Android 的 Intent.data（getter/setter），Kotlin 里以 `data = ...` 使用 */
    public android.net.Uri getData() { return data; }
    public Intent setData(android.net.Uri data) { this.data = data; return this; }

    public Intent putExtra(String name, String value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, int value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, boolean value) { extras.put(name, value); return this; }
    public String getStringExtra(String name) { return (String) extras.get(name); }
    public int getIntExtra(String name, int def) {
        Object v = extras.get(name);
        return v == null ? def : (Integer) v;
    }
    public boolean getBooleanExtra(String name, boolean def) {
        Object v = extras.get(name);
        return v == null ? def : (Boolean) v;
    }

    /** 桌面端口径的辅助方法：供界面路由读取全部参数（Android 无此方法）。 */
    public java.util.Map<String, Object> extrasSnapshot() {
        return new java.util.HashMap<>(extras);
    }
}
