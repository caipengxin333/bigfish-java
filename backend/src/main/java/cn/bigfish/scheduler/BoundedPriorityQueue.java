package cn.bigfish.scheduler;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Small bounded queue. Higher priorities first, FIFO ties; never a PriorityBlockingQueue. */
public final class BoundedPriorityQueue<T extends BoundedPriorityQueue.Item> {
    public interface Item {
        int priority();
        Instant expiresAt();
        default Instant readyAt() { return Instant.MIN; }
    }
    private record Entry<T>(long sequence, T value) {}
    private final List<Entry<T>> entries = new ArrayList<>();
    private final Consumer<T> discard;
    private long sequence;
    public BoundedPriorityQueue(Consumer<T> discard) { this.discard = discard; }

    public synchronized boolean offer(T item, int capacity, Instant now) {
        trim(capacity, now);
        if (!item.expiresAt().isAfter(now)) { discard.accept(item); return false; }
        if (entries.size() == capacity) {
            var worst = worst();
            if (item.priority() < worst.value().priority()) { discard.accept(item); return false; }
            remove(worst);
        }
        entries.add(new Entry<>(sequence++, item));
        return true;
    }
    public synchronized void trim(int capacity, Instant now) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        var expired = entries.stream().filter(e -> !e.value().expiresAt().isAfter(now)).toList();
        expired.forEach(this::remove);
        while (entries.size() > capacity) remove(worst());
    }
    public synchronized T poll(Instant now) {
        entries.stream().filter(e -> !e.value().expiresAt().isAfter(now)).toList().forEach(this::remove);
        var best = entries.stream().filter(e -> !e.value().readyAt().isAfter(now))
            .min(Comparator.<Entry<T>>comparingInt(e -> -e.value().priority()).thenComparingLong(Entry::sequence));
        if (best.isEmpty()) return null;
        entries.remove(best.get());
        return best.get().value();
    }
    public synchronized T find(Predicate<T> predicate) {
        return entries.stream().map(Entry::value).filter(predicate).findFirst().orElse(null);
    }
    /** Preserve FIFO position when coalescing a gift. */
    public synchronized void replace(T previous, T next) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).value() == previous) {
                entries.set(i, new Entry<>(entries.get(i).sequence(), next));
                return;
            }
        }
    }
    public synchronized int size() { return entries.size(); }
    public synchronized void discardWhere(Predicate<T> predicate) {
        entries.stream().filter(e -> predicate.test(e.value())).toList().forEach(this::remove);
    }
    public synchronized void clear() { new ArrayList<>(entries).forEach(this::remove); }
    private Entry<T> worst() {
        return entries.stream().min(Comparator.<Entry<T>>comparingInt(e -> e.value().priority())
            .thenComparingLong(Entry::sequence)).orElseThrow();
    }
    private void remove(Entry<T> entry) { entries.remove(entry); discard.accept(entry.value()); }
}
