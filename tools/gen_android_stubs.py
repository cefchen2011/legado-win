#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 Android API 兼容层（纯 JVM 桩实现）。

移植策略：legado 的规则引擎代码保持**原样**，通过提供同名同签名的
android/androidx 类型，让引擎源码不加修改即可在 JVM 上编译运行。
只有语义上必须真实工作的 API（TextUtils.isEmpty / Base64 / LruCache）
才给出真实实现，其余注解、常量、标记接口为空壳。
"""
import os
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "compat" / "src" / "main" / "java"
KOTLIN_SRC = ROOT / "compat" / "src" / "main" / "kotlin"

FILES = {}

# ---------------------------------------------------------------- android.text
FILES["android/text/TextUtils.java"] = """package android.text;

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
"""

# ---------------------------------------------------------------- android.util
FILES["android/util/Base64.java"] = """package android.util;

import java.nio.charset.StandardCharsets;

/** JVM 兼容实现：委托 java.util.Base64，保留 android 的 flag 常量语义。 */
public final class Base64 {
    private Base64() {}

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private static java.util.Base64.Decoder decoder(int flags) {
        return (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlDecoder()
                : java.util.Base64.getMimeDecoder();
    }

    private static java.util.Base64.Encoder encoder(int flags) {
        if ((flags & URL_SAFE) != 0) return java.util.Base64.getUrlEncoder();
        if ((flags & NO_WRAP) != 0) return java.util.Base64.getEncoder();
        return java.util.Base64.getMimeEncoder(76, new byte[]{'\\r', '\\n'});
    }

    public static byte[] decode(String str, int flags) {
        return decode(str.getBytes(StandardCharsets.US_ASCII), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        return decoder(flags).decode(input);
    }

    public static byte[] decode(String str) {
        return decode(str, DEFAULT);
    }

    public static String encodeToString(byte[] input, int flags) {
        return encoder(flags).encodeToString(input);
    }

    public static String encodeToString(byte[] input) {
        return encodeToString(input, DEFAULT);
    }

    public static byte[] encode(byte[] input, int flags) {
        return encoder(flags).encode(input);
    }

    public static byte[] encode(byte[] input) {
        return encode(input, DEFAULT);
    }
}
"""

FILES["android/util/Log.java"] = """package android.util;

/** JVM 兼容实现：输出到 stdout/stderr，行为接近 android.util.Log。 */
public final class Log {
    private Log() {}

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private static int println(int priority, String tag, String msg, Throwable tr) {
        String line = "[" + tag + "] " + msg;
        if (priority >= ERROR) System.err.println(line); else System.out.println(line);
        if (tr != null) tr.printStackTrace(priority >= ERROR ? System.err : System.out);
        return 0;
    }

    public static int v(String tag, String msg) { return println(VERBOSE, tag, msg, null); }
    public static int v(String tag, String msg, Throwable tr) { return println(VERBOSE, tag, msg, tr); }
    public static int d(String tag, String msg) { return println(DEBUG, tag, msg, null); }
    public static int d(String tag, String msg, Throwable tr) { return println(DEBUG, tag, msg, tr); }
    public static int i(String tag, String msg) { return println(INFO, tag, msg, null); }
    public static int i(String tag, String msg, Throwable tr) { return println(INFO, tag, msg, tr); }
    public static int w(String tag, String msg) { return println(WARN, tag, msg, null); }
    public static int w(String tag, String msg, Throwable tr) { return println(WARN, tag, msg, tr); }
    public static int e(String tag, String msg) { return println(ERROR, tag, msg, null); }
    public static int e(String tag, String msg, Throwable tr) { return println(ERROR, tag, msg, tr); }
    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        tr.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
    public static boolean isLoggable(String tag, int level) { return true; }
}
"""

# ------------------------------------------------------------ android.annotation
FILES["android/annotation/SuppressLint.java"] = """package android.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.CONSTRUCTOR, ElementType.LOCAL_VARIABLE})
@Retention(RetentionPolicy.CLASS)
public @interface SuppressLint {
    String[] value();
}
"""

# ---------------------------------------------------------------- android.os
FILES["android/os/Build.java"] = """package android.os;

/** JVM 兼容实现：固定报告一个足够新的 API 等级，使引擎走桌面等价分支。 */
public final class Build {
    private Build() {}

    public static final String MODEL = "Windows";
    public static final String MANUFACTURER = "Microsoft";
    public static final String BRAND = "Windows";
    public static final String DEVICE = "pc";
    public static final String PRODUCT = "legado-win";

    public static class VERSION {
        /** 对应 Android 14 (API 34)：桌面端视为现代设备。 */
        public static final int SDK_INT = 34;
        public static final String RELEASE = "14";
    }

    public static class VERSION_CODES {
        public static final int LOLLIPOP = 21;
        public static final int M = 23;
        public static final int N = 24;
        public static final int O = 26;
        public static final int P = 28;
        public static final int Q = 29;
        public static final int R = 30;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
        public static final int UPSIDE_DOWN_CAKE = 34;
    }
}
"""

FILES["android/os/Parcelable.java"] = """package android.os;

/**
 * 空标记接口：legado 的实体类实现 Parcelable 仅用于 Android 的 IPC/状态保存。
 * 桌面端不需要序列化到 Parcel，因此这里保留类型但不要求实现任何方法，
 * 使上游实体类可以原样编译。
 */
public interface Parcelable {
}
"""

FILES["android/os/Bundle.java"] = """package android.os;

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
"""

# ------------------------------------------------------------- android.webkit
FILES["android/webkit/JavascriptInterface.java"] = """package android.webkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JavascriptInterface {
}
"""

FILES["android/webkit/WebSettings.java"] = """package android.webkit;

import android.content.Context;

/** 仅提供引擎引用到的常量与静态方法。 */
public abstract class WebSettings {
    public enum LayoutAlgorithm { NORMAL, SINGLE_COLUMN, NARROW_COLUMNS, TEXT_AUTOSIZING }
    public static final int MIXED_CONTENT_ALWAYS_ALLOW = 0;
    public static final int MIXED_CONTENT_NEVER_ALLOW = 1;
    public static final int MIXED_CONTENT_COMPATIBILITY_MODE = 2;
    public static final int LOAD_DEFAULT = -1;
    public static final int LOAD_CACHE_ELSE_NETWORK = 1;
    public static final int LOAD_NO_CACHE = 2;
    public static final int LOAD_CACHE_ONLY = 3;

    /**
     * 上游用它取 WebView 的默认 UA；桌面端没有 WebView，
     * 返回等价的桌面 Chrome UA 字符串。
     */
    public static String getDefaultUserAgent(Context context) {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    }
}
"""

# ----------------------------------------------------------- androidx.annotation
FILES["androidx/annotation/Keep.java"] = """package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD,
        ElementType.PACKAGE})
@Retention(RetentionPolicy.CLASS)
public @interface Keep {
}
"""

FILES["androidx/annotation/Nullable.java"] = """package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.CLASS)
public @interface Nullable {
}
"""

FILES["androidx/annotation/NonNull.java"] = """package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.CLASS)
public @interface NonNull {
}
"""

FILES["androidx/annotation/RequiresApi.java"] = """package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
@Retention(RetentionPolicy.CLASS)
public @interface RequiresApi {
    int value() default 1;
    int api() default 1;
}
"""

# ----------------------------------------------------------- androidx.collection
FILES["androidx/collection/LruCache.java"] = """package androidx.collection;

import java.util.LinkedHashMap;
import java.util.Map;

/** 真实实现：基于 LinkedHashMap 的 LRU 缓存，行为与 androidx 版本一致。 */
public class LruCache<K, V> {
    private final LinkedHashMap<K, V> map;
    private int size;
    private int maxSize;
    private int hitCount;
    private int missCount;

    public LruCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize <= 0");
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<>(0, 0.75f, true);
    }

    public void resize(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize <= 0");
        synchronized (this) { this.maxSize = maxSize; }
        trimToSize(maxSize);
    }

    public final V get(K key) {
        if (key == null) throw new NullPointerException("key == null");
        V mapValue;
        synchronized (this) {
            mapValue = map.get(key);
            if (mapValue != null) { hitCount++; return mapValue; }
            missCount++;
        }
        V createdValue = create(key);
        if (createdValue == null) return null;
        synchronized (this) {
            map.put(key, createdValue);
            size += safeSizeOf(key, createdValue);
        }
        trimToSize(maxSize);
        return createdValue;
    }

    public final V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("key == null || value == null");
        V previous;
        synchronized (this) {
            size += safeSizeOf(key, value);
            previous = map.put(key, value);
            if (previous != null) size -= safeSizeOf(key, previous);
        }
        if (previous != null) entryRemoved(false, key, previous, value);
        trimToSize(maxSize);
        return previous;
    }

    public void trimToSize(int maxSize) {
        while (true) {
            K key;
            V value;
            synchronized (this) {
                if (size < 0 || (map.isEmpty() && size != 0)) {
                    throw new IllegalStateException(getClass().getName() + ".sizeOf() is reporting inconsistent results!");
                }
                if (size <= maxSize || map.isEmpty()) break;
                Map.Entry<K, V> toEvict = map.entrySet().iterator().next();
                key = toEvict.getKey();
                value = toEvict.getValue();
                map.remove(key);
                size -= safeSizeOf(key, value);
                evictionCount++;
            }
            entryRemoved(true, key, value, null);
        }
    }

    public final V remove(K key) {
        if (key == null) throw new NullPointerException("key == null");
        V previous;
        synchronized (this) {
            previous = map.remove(key);
            if (previous != null) size -= safeSizeOf(key, previous);
        }
        if (previous != null) entryRemoved(false, key, previous, null);
        return previous;
    }

    protected void entryRemoved(boolean evicted, K key, V oldValue, V newValue) {}
    protected V create(K key) { return null; }

    private int safeSizeOf(K key, V value) {
        int result = sizeOf(key, value);
        if (result < 0) throw new IllegalStateException("Negative size: " + key + "=" + value);
        return result;
    }

    protected int sizeOf(K key, V value) { return 1; }

    public final void evictAll() { trimToSize(-1); }

    public synchronized final int size() { return size; }
    public synchronized final int maxSize() { return maxSize; }
    public synchronized final int hitCount() { return hitCount; }
    public synchronized final int missCount() { return missCount; }
    public synchronized final int createCount() { return createCount; }
    private int createCount;
    private int evictionCount;
    public synchronized final int evictionCount() { return evictionCount; }
    public synchronized final Map<K, V> snapshot() { return new LinkedHashMap<>(map); }

    @Override
    public synchronized final String toString() {
        return String.format("LruCache[maxSize=%d,hits=%d,misses=%d,hitRate=%d%%]",
                maxSize, hitCount, missCount, hitCount * 100 / (hitCount + missCount + 1));
    }
}
"""

# ------------------------------------------------------------------ room 注解
ROOM_ANNOTATIONS = {
    "Entity": ("@Target(ElementType.TYPE)\n@Retention(RetentionPolicy.CLASS)\npublic @interface Entity {\n"
               "    String tableName() default \"\";\n    Index[] indices() default {};\n"
               "    ForeignKey[] foreignKeys() default {};\n"
               "    boolean inheritSuperIndices() default false;\n    String[] primaryKeys() default {};\n"
               "    String[] ignoredColumns() default {};\n}\n"),
    "ColumnInfo": ("@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, "
                   "ElementType.CONSTRUCTOR})\n@Retention(RetentionPolicy.CLASS)\n"
                   "public @interface ColumnInfo {\n"
                   "    String name() default \"[value-unspecified]\";\n"
                   "    boolean index() default false;\n    boolean unique() default false;\n"
                   "    int collate() default 1;\n"
                   "    String defaultValue() default \"[value-unspecified]\";\n}\n"),
    "PrimaryKey": ("@Target({ElementType.FIELD, ElementType.METHOD})\n@Retention(RetentionPolicy.CLASS)\n"
                   "public @interface PrimaryKey {\n    boolean autoGenerate() default false;\n}\n"),
    "Index": ("@Target({})\n@Retention(RetentionPolicy.CLASS)\npublic @interface Index {\n"
              "    String name() default \"\";\n    boolean unique() default false;\n    String[] value() default {};\n"
              "    String[] orders() default {};\n}\n"),
    "Ignore": ("@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})\n"
               "@Retention(RetentionPolicy.CLASS)\npublic @interface Ignore {\n}\n"),
    "Dao": ("@Target(ElementType.TYPE)\n@Retention(RetentionPolicy.CLASS)\npublic @interface Dao {\n}\n"),
    "Database": ("@Target(ElementType.TYPE)\n@Retention(RetentionPolicy.CLASS)\npublic @interface Database {\n"
                 "    Class<?>[] entities() default {};\n    Class<?>[] views() default {};\n"
                 "    int version() default 1;\n    boolean exportSchema() default true;\n}\n"),
    "Query": ("@Target(ElementType.METHOD)\n@Retention(RetentionPolicy.CLASS)\npublic @interface Query {\n"
              "    String value();\n}\n"),
    "Insert": ("@Target(ElementType.METHOD)\n@Retention(RetentionPolicy.CLASS)\npublic @interface Insert {\n"
               "    int onConflict() default 3;\n}\n"),
    "Update": ("@Target(ElementType.METHOD)\n@Retention(RetentionPolicy.CLASS)\npublic @interface Update {\n"
               "    int onConflict() default 3;\n}\n"),
    "Delete": ("@Target(ElementType.METHOD)\n@Retention(RetentionPolicy.CLASS)\npublic @interface Delete {\n}\n"),
    "RawQuery": ("@Target(ElementType.METHOD)\n@Retention(RetentionPolicy.CLASS)\npublic @interface RawQuery {\n"
                 "    String[] value();\n    boolean observedEntities() default false;\n}\n"),
    "Transaction": ("@Target(ElementType.METHOD)\n@Retention(RetentionPolicy.CLASS)\npublic @interface Transaction {\n}\n"),
    "TypeConverter": ("@Target(ElementType.METHOD)\n@Retention(RetentionPolicy.CLASS)\npublic @interface TypeConverter {\n}\n"),
    "TypeConverters": ("@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})\n"
                       "@Retention(RetentionPolicy.CLASS)\npublic @interface TypeConverters {\n    Class<?>[] value() default {};\n}\n"),
    "Embedded": ("@Target({ElementType.FIELD, ElementType.METHOD})\n@Retention(RetentionPolicy.CLASS)\n"
                 "public @interface Embedded {\n    String prefix() default \"\";\n}\n"),
    "Relation": ("@Target(ElementType.FIELD)\n@Retention(RetentionPolicy.CLASS)\npublic @interface Relation {\n"
                 "    Class<?> parentColumn();\n    Class<?> entityColumn();\n    Class<?> entity() default Object.class;\n"
                 "    String entityColumnName() default \"\";\n}\n"),
    "ForeignKey": ("@Target({})\n@Retention(RetentionPolicy.CLASS)\npublic @interface ForeignKey {\n"
                   "    Class<?> entity();\n    String[] parentColumns();\n    String[] childColumns();\n"
                   "    int onDelete() default 1;\n    int onUpdate() default 1;\n    boolean deferred() default false;\n"
                   "    int NO_ACTION = 1;\n    int RESTRICT = 2;\n    int SET_NULL = 3;\n    int SET_DEFAULT = 4;\n"
                   "    int CASCADE = 5;\n}\n"),
    "Fts4": ("@Target(ElementType.TYPE)\n@Retention(RetentionPolicy.CLASS)\npublic @interface Fts4 {\n"
             "    String[] tokenizer() default {};\n    String[] tokenizerArgs() default {};\n"
             "    String[] contentEntity() default {};\n    String[] languageId() default {};\n"
             "    String[] matchInfo() default {};\n    String[] notIndexed() default {};\n"
             "    String[] prefix() default {};\n    String[] order() default {};\n"
             "    boolean deferred() default false;\n}\n"),
    "DatabaseView": ("@Target(ElementType.TYPE)\n@Retention(RetentionPolicy.CLASS)\n"
                     "public @interface DatabaseView {\n    String value() default \"\";\n"
                     "    String viewName() default \"\";\n}\n"),
}

for name, body in ROOM_ANNOTATIONS.items():
    FILES[f"androidx/room/{name}.java"] = (
        "package androidx.room;\n\n"
        "import java.lang.annotation.ElementType;\n"
        "import java.lang.annotation.Retention;\n"
        "import java.lang.annotation.RetentionPolicy;\n"
        "import java.lang.annotation.Target;\n\n"
        "/** Room 注解的编译期空壳：桌面端不生成数据库代码，仅保留类型以兼容上游源码。 */\n"
        + body
    )

FILES["androidx/room/OnConflictStrategy.java"] = """package androidx.room;

/** Room 冲突策略常量。 */
public @interface OnConflictStrategy {
    int REPLACE = 1;
    int ROLLBACK = 2;
    int ABORT = 3;
    int FAIL = 4;
    int IGNORE = 5;
}
"""

# --------------------------------------------------------------- parcelize 标记
FILES["kotlinx/parcelize/Parcelize.java"] = """package kotlinx.parcelize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 编译期标记的空壳：桌面端不使用 Parcel，因此注解不产生任何代码。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Parcelize {
}
"""

# ------------------------------------------------------------ androidx.core.net
FILES["androidx/core/net/UriCompat.java"] = """package androidx.core.net;

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
"""


# ------------------------------------------------- parcelize / androidx 注解
FILES["androidx/annotation/IntDef.java"] = """package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 源码级标记注解。
 *
 * 注意：真实 androidx 的 value() 是 long[]，但 Kotlin 用 Int 常量作注解实参时
 * 不会自动加宽为 Long，因此这里改声明为 int[]——本注解 RetentionPolicy 为 SOURCE，
 * 运行期不做任何读取，类型差异不影响语义，却能让上游源码原样编译。
 */
@Target({ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface IntDef {
    int[] value() default {};
    boolean flag() default false;
    boolean open() default false;
}
"""

FILES["androidx/annotation/StringDef.java"] = """package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface StringDef {
    String[] value() default {};
    boolean open() default false;
}
"""

# ------------------------------------------------- android.icu（委托 java.text）
FILES["android/icu/util/ULocale.java"] = """package android.icu.util;

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
"""

FILES["android/icu/text/Collator.java"] = """package android.icu.text;

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
"""

# ------------------------------------------------- android.net.Uri
FILES["android/net/Uri.java"] = """package android.net;

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
"""

# ------------------------------------------------- android.text.Editable
FILES["android/text/Editable.java"] = """package android.text;

/**
 * JVM 兼容实现：桌面端没有 Android 的富文本编辑缓冲，
 * 这里用 StringBuilder 承载，满足引擎的实际用法。
 */
public interface Editable extends CharSequence {

    final class Factory {
        private static final Factory INSTANCE = new Factory();

        public static Factory getInstance() {
            return INSTANCE;
        }

        public Editable newEditable(CharSequence source) {
            final StringBuilder sb = new StringBuilder(source == null ? "" : source);
            return new Editable() {
                @Override
                public int length() { return sb.length(); }

                @Override
                public char charAt(int index) { return sb.charAt(index); }

                @Override
                public CharSequence subSequence(int start, int end) {
                    return sb.subSequence(start, end);
                }

                @Override
                public String toString() { return sb.toString(); }
            };
        }
    }
}
"""


# ------------------------------------------------------- Kotlin 侧兼容层
# 有些兼容类型必须是 Kotlin（扩展函数）或标注 Kotlin 专有目标（属性），
# 用 Java 桩表达不了，因此单独放在 Kotlin 源集里。
KOTLIN_FILES = {}

KOTLIN_FILES["kotlinx/parcelize/IgnoredOnParcel.kt"] = '''package kotlinx.parcelize

/**
 * 编译期标记的空壳。
 * legado 的实体类用它标注"不参与 Parcel 序列化"的派生属性；
 * 桌面端不使用 Parcel，因此注解不产生任何代码。
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class IgnoredOnParcel
'''

KOTLIN_FILES["androidx/core/net/Uri.kt"] = '''package androidx.core.net

import android.net.Uri

/** androidx.core 的 String.toUri() 扩展，桌面端直接委托给兼容层的 Uri。 */
fun String.toUri(): Uri = Uri.parse(this)
'''

KOTLIN_FILES["splitties/init/AppCtx.kt"] = '''package splitties.init

import android.content.Context

/**
 * splitties 的全局 Application Context 在桌面端的等价物。
 * legado 引擎通过它拿应用目录、字符串资源并弹出提示。
 */
val appCtx: Context = Context()
'''

KOTLIN_FILES["androidx/core/content/SharedPreferences.kt"] = '''package androidx.core.content

import android.content.SharedPreferences

/**
 * androidx.core-ktx 的 SharedPreferences.edit {} 扩展，桌面端等价实现。
 * 上游大量配置写入都走这个扩展。
 */
inline fun SharedPreferences.edit(
    commit: Boolean = false,
    action: SharedPreferences.Editor.() -> Unit,
) {
    val editor = edit()
    editor.action()
    if (commit) editor.commit() else editor.apply()
}
'''


# ------------------------------------------------- 包名/资源/Context（最小运行时）
FILES["android/content/ContextStrings.java"] = """package android.content;

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
"""

FILES["android/content/Context.java"] = """package android.content;

import java.io.File;

/**
 * 最小 Context 实现：满足 legado 引擎对应用目录与字符串资源的访问。
 *
 * 桌面端没有 Android 的应用沙箱，这里把数据根目录集中到
 * {@link #setDataRoot(File)}，默认用户主目录下的 .legado-win。
 */
public class Context {

    /**
     * 数据根目录。优先取系统属性 legado.data.root（启动器用 -D 指定），
     * 否则回落到用户主目录下的 .legado-win。
     */
    private static volatile File dataRoot = resolveDefaultRoot();

    private static File resolveDefaultRoot() {
        String configured = System.getProperty("legado.data.root");
        if (configured != null && !configured.trim().isEmpty()) {
            return new File(configured.trim());
        }
        return new File(System.getProperty("user.home"), ".legado-win");
    }

    public static void setDataRoot(File root) {
        if (root != null) dataRoot = root;
    }

    public static File getDataRoot() {
        return dataRoot;
    }

    public File getCacheDir() {
        return new File(dataRoot, "cache");
    }

    public File getFilesDir() {
        return new File(dataRoot, "files");
    }

    public File getExternalCacheDir() {
        return new File(dataRoot, "external-cache");
    }

    public File getExternalFilesDir(String type) {
        File base = new File(dataRoot, "external-files");
        return type == null ? base : new File(base, type);
    }

    public String getPackageName() {
        return "io.legado.win";
    }

    public Object getSystemService(String name) {
        return null;
    }

    public String getString(int resId) {
        return ContextStrings.get(resId);
    }

    /** 对应 Android 的 getString(int, Object...) 格式化重载 */
    public String getString(int resId, Object... formatArgs) {
        return String.format(ContextStrings.get(resId), formatArgs);
    }

    private static final java.util.Map<String, SharedPreferences> PREFS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return PREFS.computeIfAbsent(name, DesktopSharedPreferences::new);
    }

    public void startActivity(Intent intent) {
        // 桌面端没有 Activity 启动语义，由应用外壳以窗口方式承接
    }
}
"""

FILES["android/content/Intent.java"] = """package android.content;

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
"""


# ------------------------------------------------- SharedPreferences（配置持久化）
FILES["android/content/SharedPreferences.java"] = """package android.content;

import java.util.Map;
import java.util.Set;

/** JVM 兼容实现：保留 Android 的 SharedPreferences 契约。 */
public interface SharedPreferences {

    Map<String, ?> getAll();

    String getString(String key, String defValue);

    Set<String> getStringSet(String key, Set<String> defValues);

    int getInt(String key, int defValue);

    long getLong(String key, long defValue);

    float getFloat(String key, float defValue);

    boolean getBoolean(String key, boolean defValue);

    boolean contains(String key);

    Editor edit();

    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);

    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);

    interface Editor {
        Editor putString(String key, String value);

        Editor putStringSet(String key, Set<String> values);

        Editor putInt(String key, int value);

        Editor putLong(String key, long value);

        Editor putFloat(String key, float value);

        Editor putBoolean(String key, boolean value);

        Editor remove(String key);

        Editor clear();

        boolean commit();

        void apply();
    }

    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key);
    }
}
"""

FILES["android/content/DesktopSharedPreferences.java"] = """package android.content;

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

    private static final String SEP = "\\u0001";

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
"""

FILES["androidx/preference/PreferenceManager.java"] = """package androidx.preference;

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
"""


# ------------------------------------------------- media3（音频播放的最小面）
FILES["androidx/media3/common/MediaItem.java"] = """package androidx.media3.common;

/**
 * JVM 兼容实现：legado 的 AnalyzeUrl 仅为"在线朗读/音频"构造 MediaItem，
 * 引擎侧只用到 setUri 与 uri 读取，因此保留这一最小面。
 */
public class MediaItem {

    private final String uri;

    private MediaItem(String uri) {
        this.uri = uri;
    }

    public String getUri() {
        return uri;
    }

    @Override
    public String toString() {
        return "MediaItem{uri='" + uri + "'}";
    }

    public static class Builder {
        private String uri;

        public Builder setUri(String uri) {
            this.uri = uri;
            return this;
        }

        public MediaItem build() {
            return new MediaItem(uri);
        }
    }
}
"""


# ------------------------------------------------- webkit.CookieManager（网络层）
FILES["android/webkit/CookieManager.java"] = """package android.webkit;

import java.util.HashMap;
import java.util.Map;

/**
 * JVM 兼容实现：Android 的 WebView Cookie 仓库。
 * 桌面端没有 WebView，这里用进程内的 Cookie 表承载，
 * 语义与上游一致（setCookie / getCookie / removeAllCookies / flush）。
 */
public class CookieManager {

    private static final CookieManager INSTANCE = new CookieManager();

    private final Map<String, String> store = new HashMap<>();
    private boolean acceptCookie = true;

    public static CookieManager getInstance() {
        return INSTANCE;
    }

    public synchronized void setAcceptCookie(boolean accept) {
        this.acceptCookie = accept;
    }

    public synchronized boolean acceptCookie() {
        return acceptCookie;
    }

    public synchronized void setCookie(String url, String value) {
        if (url == null) return;
        store.put(url, value);
    }

    public synchronized String getCookie(String url) {
        return store.get(url);
    }

    public synchronized void removeAllCookies(ValueCallback<Boolean> callback) {
        store.clear();
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }

    public synchronized void removeSessionCookies(ValueCallback<Boolean> callback) {
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }

    public void flush() {
        // 桌面端为进程内存储，无需刷盘
    }

    public interface ValueCallback<T> {
        void onReceiveValue(T value);
    }
}
"""

FILES["android/net/http/X509TrustManagerExtensions.java"] = """package android.net.http;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.X509TrustManager;

/**
 * JVM 兼容实现：Android 用于在自定义 TrustManager 上做主机名校验的辅助类。
 * 桌面端直接委托底层 X509TrustManager。
 */
public class X509TrustManagerExtensions {

    private final X509TrustManager trustManager;

    public X509TrustManagerExtensions(X509TrustManager trustManager) {
        if (trustManager == null) throw new IllegalArgumentException("trustManager == null");
        this.trustManager = trustManager;
    }

    public List<X509Certificate> checkServerTrusted(
            X509Certificate[] chain, String authType, String host) throws CertificateException {
        trustManager.checkServerTrusted(chain, authType);
        return Arrays.asList(chain);
    }

    public boolean isUserAddedCertificate(X509Certificate cert) {
        return false;
    }

    public boolean isSameTrustConfiguration(String hostname1, String hostname2) {
        return true;
    }
}
"""


# ------------------------------------------------- 存储 / MIME / Glide 最小面
FILES["android/os/Environment.java"] = """package android.os;

import java.io.File;

/**
 * JVM 兼容实现：Android 的外部存储目录在桌面端映射到用户目录。
 */
public final class Environment {

    private Environment() {}

    /** 桌面端把"外部存储"映射到用户主目录，便于与 Android 端配置语义对齐 */
    public static File getExternalStorageDirectory() {
        return new File(System.getProperty("user.home"));
    }

    public static String getExternalStorageState() {
        return MEDIA_MOUNTED;
    }

    public static boolean isExternalStorageRemovable() {
        return false;
    }

    public static File getDataDirectory() {
        return new File(System.getProperty("user.home"));
    }

    public static File getDownloadCacheDirectory() {
        return new File(System.getProperty("java.io.tmpdir"));
    }

    public static final String MEDIA_MOUNTED = "mounted";
    public static final String MEDIA_UNMOUNTED = "unmounted";
    public static final String MEDIA_REMOVED = "removed";
    public static final String MEDIA_BAD_REMOVAL = "bad_removal";
    public static final String MEDIA_CHECKING = "checking";
    public static final String MEDIA_SHARED = "shared";
    public static final String MEDIA_MOUNTED_READ_ONLY = "mounted_ro";
    public static final String MEDIA_UNKNOWN = "unknown";
}
"""

FILES["android/webkit/MimeTypeMap.java"] = """package android.webkit;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * JVM 兼容实现：内置常见扩展名与 MIME 的映射表，
 * 覆盖阅读器实际会遇到的文本/图片/压缩包类型。
 */
public class MimeTypeMap {

    private static final MimeTypeMap INSTANCE = new MimeTypeMap();

    private static final Map<String, String> MIME = new HashMap<>();
    private static final Map<String, String> EXT = new HashMap<>();

    private static void put(String ext, String mime) {
        MIME.put(ext, mime);
        EXT.putIfAbsent(mime, ext);
    }

    static {
        put("txt", "text/plain");
        put("html", "text/html");
        put("htm", "text/html");
        put("xhtml", "application/xhtml+xml");
        put("xml", "text/xml");
        put("json", "application/json");
        put("css", "text/css");
        put("js", "application/javascript");
        put("epub", "application/epub+zip");
        put("pdf", "application/pdf");
        put("zip", "application/zip");
        put("rar", "application/x-rar-compressed");
        put("7z", "application/x-7z-compressed");
        put("png", "image/png");
        put("jpg", "image/jpeg");
        put("jpeg", "image/jpeg");
        put("gif", "image/gif");
        put("webp", "image/webp");
        put("bmp", "image/bmp");
        put("svg", "image/svg+xml");
        put("mp3", "audio/mpeg");
        put("m4a", "audio/mp4");
        put("wav", "audio/wav");
        put("ogg", "audio/ogg");
        put("mp4", "video/mp4");
    }

    public static MimeTypeMap getSingleton() {
        return INSTANCE;
    }

    public static String getFileExtensionFromUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        int slash = url.lastIndexOf('/');
        int dot = url.lastIndexOf('.');
        if (dot < 0 || dot < slash) return "";
        return url.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public String getMimeTypeFromExtension(String extension) {
        if (extension == null) return null;
        return MIME.get(extension.toLowerCase(Locale.ROOT));
    }

    public String getExtensionFromMimeType(String mimeType) {
        if (mimeType == null) return null;
        return EXT.get(mimeType.toLowerCase(Locale.ROOT));
    }

    public boolean hasExtension(String extension) {
        return extension != null && MIME.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    public boolean hasMimeType(String mimeType) {
        return mimeType != null && EXT.containsKey(mimeType.toLowerCase(Locale.ROOT));
    }
}
"""

FILES["com/bumptech/glide/load/model/GlideUrl.java"] = """package com.bumptech.glide.load.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Glide 的图片 URL 包装类。桌面端不引入 Glide，这里提供最小等价物：
 * 引擎只把它当作"带请求头的 URL"使用（toString 取回原始地址）。
 */
public class GlideUrl {

    private final String url;
    private final Map<String, String> headers;

    public GlideUrl(String url) {
        this(url, null);
    }

    public GlideUrl(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers == null ? Collections.emptyMap() : new HashMap<>(headers);
    }

    public String toStringUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    @Override
    public String toString() {
        return url;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GlideUrl && url.equals(((GlideUrl) o).url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }
}
"""

# ------------------------------------------------- 文件描述符 / 系统调用最小面
FILES["android/os/ParcelFileDescriptor.java"] = """package android.os;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;

/**
 * JVM 兼容实现：Android 的可传递文件描述符。
 * 桌面端没有 IPC 传 fd 的需求，直接包装本地文件。
 */
public class ParcelFileDescriptor implements Closeable {

    private final FileDescriptor descriptor;
    private final RandomAccessFile raf;

    /**
     * FileDescriptor -> RandomAccessFile 登记表。
     *
     * 为什么需要它：epublib 的 AndroidZipFile 是直接基于 fd 的 zip 读取器
     * （不是 java.util.zip.ZipFile），它通过 android.system.Os.read/lseek 访问文件。
     * 桌面端没有真正的 fd 层，因此由这里把 fd 映射回可随机访问的文件句柄，
     * 让 Os 的调用能真正落到文件上——否则 zip 条目会读成空。
     */
    public static final Map<FileDescriptor, RandomAccessFile> REGISTRY =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static RandomAccessFile lookup(FileDescriptor fd) {
        return REGISTRY.get(fd);
    }
    private ParcelFileDescriptor(FileDescriptor descriptor, RandomAccessFile raf) {
        this.descriptor = descriptor;
        this.raf = raf;
        if (raf != null) REGISTRY.put(descriptor, raf);
    }

    public static ParcelFileDescriptor open(File file, int mode) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, mode == MODE_READ_ONLY ? "r" : "rw");
        return new ParcelFileDescriptor(raf.getFD(), raf);
    }

    public static ParcelFileDescriptor open(File file) throws IOException {
        return open(file, MODE_READ_ONLY);
    }

    public FileDescriptor getFileDescriptor() {
        return descriptor;
    }

    public long getStatSize() {
        try {
            return raf == null ? -1 : raf.length();
        } catch (IOException e) {
            return -1;
        }
    }

    public FileInputStream createInputStream() {
        return new FileInputStream(descriptor);
    }

    public FileOutputStream createOutputStream() {
        return new FileOutputStream(descriptor);
    }

    public void close() throws IOException {
        if (raf != null) {
            REGISTRY.remove(descriptor);
            raf.close();
        }
    }

    public static final int MODE_READ_ONLY = 0x10000000;
    public static final int MODE_WRITE_ONLY = 0x20000000;
    public static final int MODE_READ_WRITE = 0x30000000;
    public static final int MODE_CREATE = 0x08000000;
    public static final int MODE_TRUNCATE = 0x04000000;
    public static final int MODE_APPEND = 0x02000000;
}
"""

FILES["android/system/OsConstants.java"] = """package android.system;

/** JVM 兼容实现：仅提供引擎引用到的常量。 */
public final class OsConstants {
    private OsConstants() {}

    public static final int SEEK_SET = 0;
    public static final int SEEK_CUR = 1;
    public static final int SEEK_END = 2;

    public static final int O_RDONLY = 0;
    public static final int O_WRONLY = 1;
    public static final int O_RDWR = 2;
}
"""

FILES["android/system/Os.java"] = """package android.system;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;

import android.os.ParcelFileDescriptor;

/**
 * JVM 兼容实现：Android 的底层 POSIX 调用。
 *
 * 这里不是空壳——epublib 的 AndroidZipFile 直接依赖 read/lseek/fstat 来解析 zip，
 * 全部实现为对 ParcelFileDescriptor 登记的真实文件句柄操作，
 * 因此 EPUB 的 zip 条目能被正确读出。
 */
public final class Os {

    private Os() {}

    private static long seek(FileDescriptor fd, long offset, int whence) throws ErrnoException {
        RandomAccessFile raf = ParcelFileDescriptor.lookup(fd);
        if (raf == null) throw new ErrnoException("lseek", 9); // EBADF
        try {
            long target;
            if (whence == OsConstants.SEEK_CUR) {
                target = raf.getFilePointer() + offset;
            } else if (whence == OsConstants.SEEK_END) {
                target = raf.length() + offset;
            } else {
                target = offset;
            }
            raf.seek(target);
            return raf.getFilePointer();
        } catch (IOException e) {
            throw new ErrnoException("lseek", 5, e);
        }
    }

    public static long lseek(FileDescriptor fd, long offset, int whence) throws ErrnoException {
        return seek(fd, offset, whence);
    }

    public static int read(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount)
            throws ErrnoException {
        RandomAccessFile raf = ParcelFileDescriptor.lookup(fd);
        if (raf == null) throw new ErrnoException("read", 9);
        try {
            return raf.read(bytes, byteOffset, byteCount);
        } catch (IOException e) {
            throw new ErrnoException("read", 5, e);
        }
    }

    public static int write(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount)
            throws ErrnoException {
        RandomAccessFile raf = ParcelFileDescriptor.lookup(fd);
        if (raf == null) throw new ErrnoException("write", 9);
        try {
            raf.write(bytes, byteOffset, byteCount);
            return byteCount;
        } catch (IOException e) {
            throw new ErrnoException("write", 5, e);
        }
    }

    public static StructStat fstat(FileDescriptor fd) throws ErrnoException {
        StructStat st = new StructStat();
        RandomAccessFile raf = ParcelFileDescriptor.lookup(fd);
        try {
            st.st_size = raf != null ? raf.length() : 0L;
        } catch (IOException e) {
            st.st_size = 0L;
        }
        return st;
    }

    public static StructStat stat(String path) throws ErrnoException {
        StructStat st = new StructStat();
        java.io.File f = new java.io.File(path);
        st.st_size = f.length();
        st.st_mode = f.isDirectory() ? 0x4000 : 0x8000;
        return st;
    }

    /** 注意：ErrnoException 是 android.system 下的顶层类，见 ErrnoException.java */
}
"""

FILES["android/system/StructStat.java"] = """package android.system;

/** JVM 兼容实现：只保留引擎实际读取的字段。 */
public final class StructStat {
    public long st_dev;
    public long st_ino;
    public int st_mode;
    public long st_nlink;
    public int st_uid;
    public int st_gid;
    public long st_rdev;
    public long st_size;
    public long st_blksize;
    public long st_blocks;
    public long st_atime;
    public long st_mtime;
    public long st_ctime;
}
"""

FILES["android/system/ErrnoException.java"] = """package android.system;

/** JVM 兼容实现：对应 Android 的 android.system.ErrnoException（顶层类）。 */
public class ErrnoException extends Exception {

    public final int errno;

    public ErrnoException(String functionName, int errno) {
        super(functionName + " failed: errno=" + errno);
        this.errno = errno;
    }

    public ErrnoException(String functionName, int errno, Throwable cause) {
        super(functionName + " failed: errno=" + errno, cause);
        this.errno = errno;
    }
}
"""


# ------------------------------------------------- android.graphics（用 JDK 的 ImageIO 真实现）
FILES["android/graphics/Bitmap.java"] = """package android.graphics;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * JVM 兼容实现：桌面端没有 Skia，改用 JDK 自带的 ImageIO。
 * 这是**真实实现**而非空壳——legado 用它把 EPUB 封面转成 JPEG 落盘。
 */
public final class Bitmap {

    private final BufferedImage image;

    Bitmap(BufferedImage image) {
        this.image = image;
    }

    public static Bitmap wrap(BufferedImage image) {
        return new Bitmap(image);
    }

    public BufferedImage unwrap() {
        return image;
    }

    public int getWidth() {
        return image == null ? 0 : image.getWidth();
    }

    public int getHeight() {
        return image == null ? 0 : image.getHeight();
    }

    public boolean isRecycled() {
        return false;
    }

    public void recycle() {
        // Java 有 GC，无需手动回收
    }

    public enum CompressFormat { JPEG, PNG, WEBP }

    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        if (image == null) return false;
        try {
            String fmt = format == CompressFormat.PNG ? "png" : "jpeg";
            if ("jpeg".equals(fmt)) {
                // JPEG 走 quality 参数；PNG 直接写
                java.util.Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName(fmt);
                if (!it.hasNext()) return ImageIO.write(image, "png", stream);
                ImageWriter writer = it.next();
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(stream)) {
                    writer.setOutput(ios);
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    if (param.canWriteCompressed()) {
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        param.setCompressionQuality(Math.max(0f, Math.min(1f, quality / 100f)));
                    }
                    // JPEG 不支持 alpha 通道，先转成 RGB
                    BufferedImage rgb = new BufferedImage(
                            image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                    rgb.createGraphics().drawImage(image, 0, 0, java.awt.Color.WHITE, null);
                    writer.write(null, new IIOImage(rgb, null, null), param);
                } finally {
                    writer.dispose();
                }
                return true;
            }
            return ImageIO.write(image, "png", stream);
        } catch (IOException e) {
            return false;
        }
    }

    public static Bitmap createBitmap(int width, int height) {
        return new Bitmap(new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB));
    }
}
"""

FILES["android/graphics/BitmapFactory.java"] = """package android.graphics;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * JVM 兼容实现：基于 ImageIO 解码图片。
 * 支持 png / jpg / gif / bmp；webp 需要额外插件，解码失败时返回 null（上游同样是可空返回）。
 */
public final class BitmapFactory {

    private BitmapFactory() {}

    public static Bitmap decodeStream(InputStream is) {
        if (is == null) return null;
        try {
            BufferedImage img = ImageIO.read(is);
            return img == null ? null : Bitmap.wrap(img);
        } catch (IOException e) {
            return null;
        }
    }

    public static Bitmap decodeFile(String pathName) {
        try (InputStream in = new FileInputStream(pathName)) {
            return decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    public static Bitmap decodeFile(File file) {
        return decodeFile(file.getAbsolutePath());
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        if (data == null) return null;
        try (InputStream in = new java.io.ByteArrayInputStream(data, offset, length)) {
            return decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options opts) {
        return decodeByteArray(data, offset, length);
    }

    /** 上游只用到 inJustDecodeBounds 做尺寸探测；这里保留字段形状 */
    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public int outWidth;
        public int outHeight;
    }
}
"""


def main():
    written = 0
    for rel, content in FILES.items():
        path = SRC / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        written += 1
    for rel, content in KOTLIN_FILES.items():
        path = KOTLIN_SRC / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        written += 1
    print(f"生成 {written} 个兼容层文件 -> {SRC.parent}")


if __name__ == "__main__":
    main()
