package cn.bigfish.collector;

import cn.bigfish.contract.LiveEvent;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class EventNormalizerTest {
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private final EventNormalizer normalizer = new EventNormalizer("session-a", CollectorOptions.defaults(), new CollectorDiagnostics());

    static IncomingMessage gift(String id, String user, String group, long count) {
        return new IncomingMessage("GIFT", id, "internal-room", user, "测试观众", 1234,
                "", "gift-1", "测试礼物", count, group, null, 0);
    }

    @Test void singleGiftHasQuantityOneAndUnknownValueIsNull() {
        LiveEvent event = normalizer.normalize(gift("one", "user", "", 0), NOW).orElseThrow();
        assertEquals(1, event.schemaVersion()); assertEquals("session-a", event.sessionId());
        assertEquals(NOW, event.receivedAt()); assertEquals("internal-room", event.roomId());
        assertEquals(1, event.payload().get("quantity")); assertNull(event.payload().get("unitValue"));
        assertTrue(event.payload().containsKey("unitValue"));
        assertEquals("DOUYIN_DIAMOND", event.payload().get("valueUnit"));
        assertThrows(UnsupportedOperationException.class, () -> event.payload().put("quantity", 99));
    }

    @Test void cumulativeCountersDeduplicateFinalPacketsAndOutOfOrderUpdates() {
        assertEquals(1, normalizer.normalize(gift("1", "user", "group", 1), NOW).orElseThrow().payload().get("quantity"));
        assertEquals(2, normalizer.normalize(gift("2", "user", "group", 3), NOW).orElseThrow().payload().get("quantity"));
        assertTrue(normalizer.normalize(gift("2", "user", "group", 3), NOW).isEmpty());
        assertTrue(normalizer.normalize(gift("final", "user", "group", 3), NOW).isEmpty());
        assertTrue(normalizer.normalize(gift("late", "user", "group", 2), NOW).isEmpty());
        assertEquals(1, normalizer.normalize(gift("3", "user", "group", 4), NOW).orElseThrow().payload().get("quantity"));
    }

    @Test void differentUsersAndGroupsDoNotMergeAndRepeatedIdsDoNotAdvanceCounter() {
        normalizer.normalize(gift("1", "a", "group", 1), NOW);
        assertTrue(normalizer.normalize(gift("1", "a", "group", 3), NOW).isEmpty());
        assertEquals(2, normalizer.normalize(gift("2", "a", "group", 3), NOW).orElseThrow().payload().get("quantity"));
        assertEquals(1, normalizer.normalize(gift("3", "b", "group", 1), NOW).orElseThrow().payload().get("quantity"));
        assertEquals(1, normalizer.normalize(gift("4", "a", "other", 1), NOW).orElseThrow().payload().get("quantity"));
    }

    @Test void missingIdentityNeverCombinesUsersByNickname() {
        assertEquals(1, normalizer.normalize(gift("1", "", "group", 1), NOW).orElseThrow().payload().get("quantity"));
        assertEquals(1, normalizer.normalize(gift("2", "", "group", 1), NOW).orElseThrow().payload().get("quantity"));
    }

    @Test void absentMessageIdsUseStableFieldsRatherThanReceptionTime() {
        var input = gift("", "a", "", 1);
        assertTrue(normalizer.normalize(input, NOW).orElseThrow().eventId().startsWith("derived-"));
        assertTrue(normalizer.normalize(input, NOW.plusSeconds(1)).isEmpty());
        var ambiguous = new IncomingMessage("COMMENT", "", "room", "user", "", 0, "hello", "", "", 0, "", null, 0);
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(ambiguous, NOW));
    }

    @Test void cachesExpireAndHaveCapacityAndSessionScope() {
        var defaults = CollectorOptions.defaults();
        var options = new CollectorOptions(2, Duration.ofSeconds(1), 1, Duration.ofSeconds(2),
                defaults.reconnectBase(), defaults.reconnectMax(), defaults.heartbeatInterval(), defaults.idleTimeout(), defaults.maxFrameBytes());
        var limited = new EventNormalizer("session", options, new CollectorDiagnostics());
        var a = gift("1", "a", "group", 1);
        limited.normalize(a, NOW);
        assertTrue(limited.normalize(a, NOW.plusSeconds(1)).isEmpty());
        assertTrue(limited.normalize(a, NOW.plusSeconds(2)).isPresent());
        limited.normalize(gift("2", "b", "other", 1), NOW.plusSeconds(2));
        assertTrue(limited.normalize(a, NOW.plusSeconds(2)).isPresent());
        assertTrue(new EventNormalizer("new-session", options, new CollectorDiagnostics()).normalize(a, NOW).isPresent());
    }

    @Test void negativeAndOverflowCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(gift("1", "a", "", -1), NOW));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(gift("2", "a", "", Long.MAX_VALUE), NOW));
    }
}
