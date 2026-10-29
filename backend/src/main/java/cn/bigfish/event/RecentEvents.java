package cn.bigfish.event;

import java.time.*;
import java.util.LinkedHashMap;

/** Owned by the ingress lock; both age and cardinality are bounded. */
public final class RecentEvents {
    private final LinkedHashMap<String, Instant> entries = new LinkedHashMap<>();
    public boolean contains(String key, Instant now) {
        entries.entrySet().removeIf(e -> !e.getValue().isAfter(now));
        return entries.containsKey(key);
    }
    public void remember(String key, Instant now) {
        if (entries.size() >= 4096) entries.remove(entries.keySet().iterator().next());
        entries.put(key, now.plus(Duration.ofMinutes(5)));
    }
    public int size() { return entries.size(); }
    public void clear() { entries.clear(); }
}
