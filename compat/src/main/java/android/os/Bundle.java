package android.os;

import java.util.HashMap;
import java.util.Map;

/** 最小实现：仅满足编译与简单的键值存取。 */
public class Bundle {
    private final Map<String, Object> map = new HashMap<>();

    public void putString(String key, String value) { map.put(key, value); }
    public String getString(String key) { return (String) map.get(key); }
    public void putInt(String key, int value) { map.put(key, value); }
    public int getInt(String key) { return map.get(key) == null ? 0 : (Integer) map.get(key); }
    public void putLong(String key, long value) { map.put(key, value); }
    public long getLong(String key) { return map.get(key) == null ? 0L : (Long) map.get(key); }
    public void putBoolean(String key, boolean value) { map.put(key, value); }
    public boolean getBoolean(String key) { return map.get(key) != null && (Boolean) map.get(key); }
    public Object get(String key) { return map.get(key); }
    public void putAll(Bundle other) { map.putAll(other.map); }
    public boolean containsKey(String key) { return map.containsKey(key); }
    public int size() { return map.size(); }
}
