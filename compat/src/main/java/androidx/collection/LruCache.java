package androidx.collection;

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
