package cn.bigfish.collector;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

/** Confined to a single source thread. Both caches and queues have explicit retention limits. */
final class ExpiringCache<K, V> {
    private record Entry<V>(V value, Instant expiresAt) { }
    private final LinkedHashMap<K, Entry<V>> values = new LinkedHashMap<>();
    private final int capacity;
    private final Duration ttl;

    ExpiringCache(int capacity, Duration ttl) { this.capacity = capacity; this.ttl = ttl; }

    V get(K key, Instant now) {
        expire(now);
        var entry = values.get(key);
        return entry == null ? null : entry.value();
    }

    void put(K key, V value, Instant now) {
        expire(now);
        values.remove(key);
        values.put(key, new Entry<>(value, now.plus(ttl)));
        while (values.size() > capacity) values.pollFirstEntry();
    }

    private void expire(Instant now) {
        while (!values.isEmpty() && !values.firstEntry().getValue().expiresAt().isAfter(now)) {
            values.pollFirstEntry();
        }
    }
}
