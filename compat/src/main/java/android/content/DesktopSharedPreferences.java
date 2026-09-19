package android.content;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 桌面端 SharedPreferences 实现：以 Properties 文件持久化到应用数据目录。
 *
 * 值按类型加前缀存储（B:/I:/L:/F:/S:/SS:），以保留 getInt / getString 等
 * 取值的类型语义——这是上游大量配置项正确工作的前提。
 */
public class DesktopSharedPreferences implements SharedPreferences {

    private static final String SEP = "\u0001";

    private final File file;
    private final Properties props = new Properties();
    private final CopyOnWriteArrayList<OnSharedPreferenceChangeListener> listeners =
            new CopyOnWriteArrayList<>();

    public DesktopSharedPreferences(String name) {
        File dir = new File(Context.getDataRoot(), "prefs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        this.file = new File(dir, name + ".properties");
        load();
    }

    private synchronized void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException ignored) {
            // 首次运行或文件损坏：以空配置继续
        }
    }

    private synchronized void save() {
        try (FileOutputStream out = new FileOutputStream(file)) {
            props.store(out, "legado-win desktop preferences");
        } catch (IOException ignored) {
            // 落盘失败不影响内存态
        }
    }

    private String raw(String key) {
        return props.getProperty(key);
    }

    private void put(String key, String encoded) {
        props.setProperty(key, encoded);
    }

    @Override
    public synchronized Map<String, ?> getAll() {
        Map<String, Object> out = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            out.put(key, decode(raw(key)));
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public synchronized String getString(String key, String defValue) {
        Object v = decode(raw(key));
        return v instanceof String ? (String) v : defValue;
    }

    @SuppressWarnings("unchecked")
    @Override
    public synchronized Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = decode(raw(key));
        return v instanceof Set ? (Set<String>) v : defValues;
    }

    @Override
    public synchronized int getInt(String key, int defValue) {
        Object v = decode(raw(key));
        return v instanceof Integer ? (Integer) v : defValue;
    }

    @Override
    public synchronized long getLong(String key, long defValue) {
        Object v = decode(raw(key));
        return v instanceof Long ? (Long) v : defValue;
    }

    @Override
    public synchronized float getFloat(String key, float defValue) {
        Object v = decode(raw(key));
        return v instanceof Float ? (Float) v : defValue;
    }

    @Override
    public synchronized boolean getBoolean(String key, boolean defValue) {
        Object v = decode(raw(key));
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    @Override
    public synchronized boolean contains(String key) {
        return props.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new DesktopEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        listeners.addIfAbsent(l);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        listeners.remove(l);
    }

    private void notifyChanged(String key) {
        for (OnSharedPreferenceChangeListener l : listeners) {
            l.onSharedPreferenceChanged(this, key);
        }
    }

    private static Object decode(String encoded) {
        if (encoded == null || encoded.length() < 2) return null;
        String body = encoded.substring(2);
        try {
            switch (encoded.substring(0, 2)) {
                case "B:":
                    return Boolean.parseBoolean(body);
                case "I:":
                    return Integer.parseInt(body);
                case "L:":
                    return Long.parseLong(body);
                case "F:":
                    return Float.parseFloat(body);
                case "SS": {
                    Set<String> set = new LinkedHashSet<>();
                    if (!body.isEmpty()) {
                        for (String s : body.split(SEP, -1)) set.add(s);
                    }
                    return set;
                }
                case "S:":
                default:
                    return body;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String encode(Object value) {
        if (value instanceof Boolean) return "B:" + value;
        if (value instanceof Integer) return "I:" + value;
        if (value instanceof Long) return "L:" + value;
        if (value instanceof Float) return "F:" + value;
        if (value instanceof Set) {
            StringBuilder sb = new StringBuilder("SS");
            for (Object o : (Set<?>) value) sb.append(SEP).append(o);
            return sb.toString();
        }
        return "S:" + value;
    }

    private final class DesktopEditor implements Editor {

        private final Map<String, String> staged = new HashMap<>();
        private final Set<String> removals = new LinkedHashSet<>();
        private boolean clearAll = false;

        @Override
        public Editor putString(String key, String value) {
            staged.put(key, encode(value));
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            staged.put(key, encode(values));
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            staged.put(key, encode(value));
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            staged.put(key, encode(value));
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            staged.put(key, encode(value));
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            staged.put(key, encode(value));
            return this;
        }

        @Override
        public Editor remove(String key) {
            removals.add(key);
            return this;
        }

        @Override
        public Editor clear() {
            clearAll = true;
            return this;
        }

        @Override
        public boolean commit() {
            applyChanges();
            return true;
        }

        @Override
        public void apply() {
            applyChanges();
        }

        private void applyChanges() {
            synchronized (DesktopSharedPreferences.this) {
                if (clearAll) props.clear();
                for (String key : removals) props.remove(key);
                for (Map.Entry<String, String> e : staged.entrySet()) {
                    props.setProperty(e.getKey(), e.getValue());
                }
                save();
            }
            for (String key : removals) notifyChanged(key);
            for (String key : staged.keySet()) notifyChanged(key);
        }
    }
}
