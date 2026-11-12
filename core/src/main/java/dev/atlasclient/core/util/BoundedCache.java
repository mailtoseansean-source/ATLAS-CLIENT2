package dev.atlasclient.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Thread-safe LRU map with a hard size limit. Used for chunk analysis results and debris records
 * so memory stays bounded no matter how far the player travels.
 */
public final class BoundedCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map;
    private long evictions;

    public BoundedCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(Math.min(capacity, 1024), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                if (size() > BoundedCache.this.capacity) {
                    evictions++;
                    return true;
                }
                return false;
            }
        };
    }

    public synchronized V get(K key) { return map.get(key); }

    public synchronized void put(K key, V value) { map.put(key, value); }

    public synchronized V remove(K key) { return map.remove(key); }

    public synchronized boolean containsKey(K key) { return map.containsKey(key); }

    public synchronized int size() { return map.size(); }

    public int capacity() { return capacity; }

    public synchronized long evictions() { return evictions; }

    public synchronized void clear() { map.clear(); }

    public synchronized List<V> values() { return new ArrayList<>(map.values()); }

    public synchronized List<K> keys() { return new ArrayList<>(map.keySet()); }

    public synchronized int removeIf(Predicate<V> predicate) {
        int before = map.size();
        map.values().removeIf(predicate);
        return before - map.size();
    }
}
