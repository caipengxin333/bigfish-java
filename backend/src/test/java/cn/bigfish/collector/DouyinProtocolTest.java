package cn.bigfish.collector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;
import static cn.bigfish.collector.WireFixtures.*;

class DouyinProtocolTest {
    private final CollectorDiagnostics diagnostics = new CollectorDiagnostics();
    private final DouyinProtocol protocol = new DouyinProtocol(2 * 1024 * 1024, diagnostics);

    @Test void chatIdsStayStringsBeyondJavaScriptSafeIntegerRange() throws Exception {
        var message = protocol.decode(frame(false, false, chat(9_007_199_254_740_993L)), "123").messages().getFirst();
        assertEquals("9007199254740993", message.messageId()); assertEquals("123", message.roomId());
        assertEquals("456", message.userId()); assertEquals("你好", message.content());
        assertEquals("COMMENT", message.kind());
        assertEquals("18446744073709551615", ProtoWire.parse(new WireFixtures().number(1, -1L).build()).id(1));
    }

    @Test void gzipAndBothGiftCounterWireVariantsAreSupported() throws Exception {
        var batch = protocol.decode(frame(true, true, gift(1, 7, 5, 1, false), gift(2, 7, 5, 3, true)), "123");
        assertEquals(2, batch.messages().size());
        assertEquals(1, batch.messages().getFirst().cumulativeQuantity());
        var second = batch.messages().getLast();
        assertEquals(3, second.cumulativeQuantity()); assertEquals("5", second.comboId());
        assertEquals("8", second.giftId()); assertEquals(1L, second.unitValue());
        var ack = ProtoWire.parse(batch.acknowledgement());
        assertEquals(91, ack.number(2)); assertEquals("ack", ack.text(7)); assertEquals("synthetic-extension", ack.text(8));
    }

    @Test void ackAndHeartbeatMatchIndependentGoldenWireBytes() {
        var hex = HexFormat.of();
        assertArrayEquals(hex.parseHex("3a026862"), ProtoWire.heartbeat());
        assertArrayEquals(hex.parseHex("105b3a0361636b420178"), ProtoWire.ack(91, "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void malformedEmbeddedMessageWrongRoomAndUnknownTypeDoNotHideNextMessage() throws Exception {
        var invalidUtf8 = envelope("WebcastChatMessage", new WireFixtures().bytes(1, common(1, 123))
                .bytes(3, new byte[]{(byte) 0xc3, 0x28}).build());
        var unknown = envelope("FutureMessage", new byte[]{0});
        var messages = protocol.decode(frame(false, false, new byte[]{0}, invalidUtf8, end(999), unknown, chat(5)), "123").messages();
        assertEquals(1, messages.size()); assertEquals("5", messages.getFirst().messageId());
        assertEquals(3, diagnostics.count(MALFORMED)); assertEquals(1, diagnostics.count(UNKNOWN));
    }

    @Test void socialSharesAreNotReportedAsFollowsAndEndRequiresStatusThree() throws Exception {
        var follow = envelope("WebcastSocialMessage", new WireFixtures().bytes(1, common(1, 123)).number(4, 1).build());
        var share = envelope("WebcastSocialMessage", new WireFixtures().bytes(1, common(2, 123)).number(4, 3).build());
        var otherControl = envelope("WebcastControlMessage", new WireFixtures().bytes(1, common(3, 123)).number(2, 1).build());
        var messages = protocol.decode(frame(false, false, follow, share, otherControl, end(123)), "123").messages();
        assertEquals(2, messages.size()); assertEquals("FOLLOW", messages.getFirst().kind()); assertEquals("ENDED", messages.getLast().kind());
    }

    @Test void giftValueIsUnknownWhenFieldAbsentAndZeroWhenExplicitlyPresent() throws Exception {
        var gift = envelope("WebcastGiftMessage", new WireFixtures().bytes(1, common(1, 123)).number(2, 8).build());
        assertNull(protocol.decode(frame(false, false, gift), "123").messages().getFirst().unitValue());
        var zeroValue = envelope("WebcastGiftMessage", new WireFixtures().bytes(1, common(2, 123)).number(2, 8)
                .bytes(15, new WireFixtures().number(12, 0).build()).build());
        assertEquals(0L, protocol.decode(frame(false, false, zeroValue), "123").messages().getFirst().unitValue());
    }

    @Test void unmarkedGzipIsDetectedButInvalidCompressedAndOversizedFramesAreRejected() throws Exception {
        byte[] compressed = gzip(new WireFixtures().bytes(1, chat(1)).build());
        var unmarked = new WireFixtures().bytes(8, compressed).build();
        assertEquals(1, protocol.decode(unmarked, "123").messages().size());
        var invalidGzip = new WireFixtures().text(6, "gzip").bytes(8, new byte[]{1, 2, 3}).build();
        assertThrows(IOException.class, () -> protocol.decode(invalidGzip, "123"));
        var small = new DouyinProtocol(128, diagnostics);
        assertThrows(IllegalArgumentException.class, () -> small.decode(wrap(true, new byte[129]), "123"));
        assertThrows(IllegalArgumentException.class, () -> small.decode(new byte[129], "123"));
    }

    @Test void wireReaderRejectsZeroTagsTruncationOverflowAndExcessiveFieldCounts() {
        for (String hex : new String[]{"00", "08", "0affff", "088080808080808080808002", "0b", "0a800000"}) {
            assertThrows(IllegalArgumentException.class, () -> ProtoWire.parse(HexFormat.of().parseHex(hex)), hex);
        }
        var tooMany = new WireFixtures(); for (int i = 0; i < 4097; i++) tooMany.number(1, 1);
        assertThrows(IllegalArgumentException.class, () -> ProtoWire.parse(tooMany.build()));
        // Valid unknown fixed fields can be skipped, preserving recognized fields after them.
        var fixed = ProtoWire.parse(HexFormat.of().parseHex("f9050000000000000000082a"));
        assertEquals(42, fixed.number(1));
        var wrongType = ProtoWire.parse(HexFormat.of().parseHex("0d00000000"));
        assertThrows(IllegalArgumentException.class, () -> wrongType.number(1));
    }
}
