package cn.bigfish.core;

import cn.bigfish.contract.LiveEvent;
import cn.bigfish.event.*;
import cn.bigfish.scheduler.BoundedPriorityQueue;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IngressPrimitivesTest {
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    record Item(int id, int priority, Instant expiresAt) implements BoundedPriorityQueue.Item {}

    @Test void floodStaysBoundedAndProtectsHighPriorityWork() {
        var discarded = new ArrayList<Item>();
        var q = new BoundedPriorityQueue<Item>(discarded::add);
        q.offer(new Item(-1, 60, NOW.plusSeconds(10)), 8, NOW);
        for (int i = 0; i < 10_000; i++) {
            q.offer(new Item(i, 50, NOW.plusSeconds(10)), 8, NOW);
            assertThat(q.size()).isLessThanOrEqualTo(8);
        }
        assertThat(q.poll(NOW).id()).isEqualTo(-1);
        assertThat(q.poll(NOW).id()).isEqualTo(9993);
        assertThat(discarded).hasSize(9993);
    }
    @Test void rejectsLowerPriorityAndDropsExpiredOnBothOfferAndPoll() {
        var q = new BoundedPriorityQueue<Item>(ignored -> {});
        assertThat(q.offer(new Item(1, 60, NOW.plusSeconds(1)), 1, NOW)).isTrue();
        assertThat(q.offer(new Item(2, 5, NOW.plusSeconds(1)), 1, NOW)).isFalse();
        assertThat(q.poll(NOW.plusSeconds(1))).isNull();
        assertThat(q.offer(new Item(3, 100, NOW), 1, NOW)).isFalse();
    }
    @Test void serverTimeAndImmutablePayloadAreUsed() {
        var payload = new HashMap<String,Object>(Map.of("content", "你好"));
        var e = new LiveEvent(1,"e","s","room",LiveEvent.Type.COMMENT,"u","昵称\n",Instant.MAX,payload);
        var normalized = EventValidator.normalize(e, NOW);
        payload.put("content", "changed");
        assertThat(normalized.receivedAt()).isEqualTo(NOW);
        assertThat(normalized.username()).isEqualTo("昵称");
        assertThat(normalized.payload()).containsEntry("content", "你好");
        assertThatThrownBy(() -> normalized.payload().put("extra", true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> EventValidator.normalize(new LiveEvent(1,"e","s","room",
            LiveEvent.Type.LIKE,"u","",NOW,Map.of("count", 1.5)), NOW)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void dedupHasFiniteCapacityAndTtl() {
        var recent = new RecentEvents();
        for (int i = 0; i < 10_000; i++) recent.remember("s:" + i, NOW);
        assertThat(recent.size()).isEqualTo(4096);
        assertThat(recent.contains("s:9999", NOW)).isTrue();
        assertThat(recent.contains("s:9999", NOW.plusSeconds(301))).isFalse();
    }
}
